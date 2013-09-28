package com.taobao.metamorphosis.client.consumer;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.RejectedExecutionException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.taobao.gecko.service.exception.NotifyRemotingException;
import com.taobao.metamorphosis.Message;
import com.taobao.metamorphosis.MessageAccessor;
import com.taobao.metamorphosis.cluster.Partition;
import com.taobao.metamorphosis.exception.InvalidMessageException;
import com.taobao.metamorphosis.exception.MetaClientException;
import com.taobao.metamorphosis.utils.MetaStatLog;
import com.taobao.metamorphosis.utils.StatConstants;


/**
 * 消息抓取管理器的实现
 * 
 * @author boyan(boyan@taobao.com)
 * @date 2011-9-13
 * 
 */
public class SimpleFetchManager implements FetchManager {

    private volatile boolean shutdown = false;

    private Thread[] fetchRunners;

    private int fetchRequestCount;

    private FetchRequestQueue requestQueue;

    private final ConsumerConfig consumerConfig;

    private final InnerConsumer consumer;


    public SimpleFetchManager(final ConsumerConfig consumerConfig, final InnerConsumer consumer) {
        super();
        this.consumerConfig = consumerConfig;
        this.consumer = consumer;
    }


    @Override
    public boolean isShutdown() {
        return this.shutdown;
    }


    @Override
    public void stopFetchRunner() throws InterruptedException {
        this.shutdown = true;
        // 中断所有任务
        if (this.fetchRunners != null) {
            for (final Thread thread : this.fetchRunners) {
                if (thread != null) {
                    thread.interrupt();
                    try {
                        thread.join(5000);
                    }
                    catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

            }
        }
        // 等待所有任务结束
        if (this.requestQueue != null) {
            this.requestQueue.shutdown();

            while (this.requestQueue.size() != this.fetchRequestCount) {
                Thread.sleep(50);
            }
        }

    }


    @Override
    public void resetFetchState() {
        this.requestQueue = new FetchRequestQueue();
        this.fetchRunners = new Thread[this.consumerConfig.getFetchRunnerCount()];
        for (int i = 0; i < this.fetchRunners.length; i++) {
            this.fetchRunners[i] = new Thread(new FetchRequestRunner());
            this.fetchRunners[i].setName(this.consumerConfig.getGroup() + "Fetch-Runner-" + i);
        }

    }


    @Override
    public void startFetchRunner() {
        // 保存请求数目，在停止的时候要检查
        this.fetchRequestCount = this.requestQueue.size();
        this.shutdown = false;
        for (final Thread thread : this.fetchRunners) {
            thread.start();
        }

    }


    @Override
    public void addFetchRequest(final FetchRequest request) {
        this.requestQueue.offer(request);

    }


    FetchRequest takeFetchRequest() throws InterruptedException {
        return this.requestQueue.take();
    }

    static final Log log = LogFactory.getLog(SimpleFetchManager.class);

    class FetchRequestRunner implements Runnable {

        private static final int DELAY_NPARTS = 10;


        @Override
        public void run() {
            while (!SimpleFetchManager.this.shutdown) {
                try {
                    final FetchRequest request = SimpleFetchManager.this.requestQueue.take();
                    if (request != null) {
                        this.executeRequest(request);
                    }
                }
                catch (final InterruptedException e) {
                    // take响应中断，忽略
                }

            }
        }


        void executeRequest(final FetchRequest request) {
            try {
                final FetchResult fetchResult = SimpleFetchManager.this.consumer.fetchAll(request, -1, null);
                if (fetchResult != null) {
                    // 2.0
                    if (fetchResult.isNewMetaServer()) {
                        List<Message> msgList = fetchResult.getMessageList();
                        final ListIterator<Message> iterator = msgList.listIterator();
                        final MessageListener listener =
                                SimpleFetchManager.this.consumer.getMessageListener(request.getTopic());
                        this.notifyListener20(request, iterator, listener);
                    }
                    // 1.4
                    else {
                        final MessageIterator iterator = fetchResult.getMessageIterator();
                        final MessageListener listener =
                                SimpleFetchManager.this.consumer.getMessageListener(request.getTopic());
                        this.notifyListener(request, iterator, listener);
                    }
                }
                else {
                    this.updateDelay(request);
                    SimpleFetchManager.this.addFetchRequest(request);
                }
            }
            catch (final MetaClientException e) {
                this.updateDelay(request);
                this.LogAddRequest(request, e);
            }
            catch (final InterruptedException e) {
                // 仍然需要加入队列，可能是停止信号
                SimpleFetchManager.this.addFetchRequest(request);
            }
            catch (final Throwable e) {
                this.updateDelay(request);
                this.LogAddRequest(request, e);
            }
        }

        private long lastLogNoConnectionTime;


        private void LogAddRequest(final FetchRequest request, final Throwable e) {
            if (e instanceof MetaClientException && e.getCause() instanceof NotifyRemotingException
                    && e.getMessage().contains("无可用连接")) {
                // 最多1分钟打印一次
                final long now = System.currentTimeMillis();
                if (this.lastLogNoConnectionTime <= 0 || (now - this.lastLogNoConnectionTime) > 60000) {
                    log.error("获取消息失败,topic=" + request.getTopic() + ",partition=" + request.getPartition(), e);
                    this.lastLogNoConnectionTime = now;
                }
            }
            else {
                log.error("获取消息失败,topic=" + request.getTopic() + ",partition=" + request.getPartition(), e);
            }
            SimpleFetchManager.this.addFetchRequest(request);
        }


        private void getOffsetAddRequest(final FetchRequest request, final InvalidMessageException e) {
            try {
                final long newOffset = SimpleFetchManager.this.consumer.offset(request);
                request.resetRetries();
                request.setOffset(newOffset, request.getLastMessageId(), request.getPartitionObject().isAutoAck());
            }
            catch (final MetaClientException ex) {
                log.error("查询offset失败,topic=" + request.getTopic() + ",partition=" + request.getPartition(), e);
            }
            finally {
                SimpleFetchManager.this.addFetchRequest(request);
            }
        }


        private void notifyListener(final FetchRequest request, final MessageIterator it,
                final MessageListener listener) {
            if (listener != null) {
                if (listener.getExecutor() != null) {
                    try {
                        listener.getExecutor().execute(new Runnable() {
                            @Override
                            public void run() {
                                FetchRequestRunner.this.receiveMessages(request, it, listener);
                            }
                        });
                    }
                    catch (final RejectedExecutionException e) {
                        log.error("MessageListener线程池繁忙，无法处理消息,topic=" + request.getTopic() + ",partition="
                                + request.getPartition(), e);
                        SimpleFetchManager.this.addFetchRequest(request);
                    }

                }
                else {
                    this.receiveMessages(request, it, listener);
                }
            }
        }


        private void notifyListener20(final FetchRequest request, final ListIterator<Message> it,
                final MessageListener listener) {
            if (listener != null) {
                if (listener.getExecutor() != null) {
                    try {
                        listener.getExecutor().execute(new Runnable() {
                            @Override
                            public void run() {
                                FetchRequestRunner.this.receiveMessages20(request, it, listener);
                            }
                        });
                    }
                    catch (final RejectedExecutionException e) {
                        log.error("MessageListener线程池繁忙，无法处理消息,topic=" + request.getTopic() + ",partition="
                                + request.getPartition(), e);
                        SimpleFetchManager.this.addFetchRequest(request);
                    }

                }
                else {
                    this.receiveMessages20(request, it, listener);
                }
            }
        }


        /**
         * 处理消息的整个流程：<br>
         * <ul>
         * <li>1.判断是否有消息可以处理，如果没有消息并且有数据递增重试次数，并判断是否需要递增maxSize</li>
         * <li>2.判断消息是否重试多次，如果超过设定次数，就跳过该消息继续往下走。跳过的消息可能在本地重试或者交给notify重投</li>
         * <li>3.进入消息处理流程，根据是否自动ack的情况进行处理:
         * <ul>
         * <li>(1)如果消息是自动ack，如果消费发生异常，则不修改offset，延迟消费等待重试</li>
         * <li>(2)如果消息是自动ack，如果消费正常，递增offset</li>
         * <li>(3)如果消息非自动ack，如果消费正常并ack，将offset修改为tmp offset，并重设tmp offset</li>
         * <li>(4)如果消息非自动ack，如果消费正常并rollback，不递增offset，重设tmp offset</li>
         * <li>(5)如果消息非自动ack，如果消费正常不ack也不rollback，不递增offset，递增tmp offset</li>
         * </ul>
         * </li>
         * </ul>
         * 
         * @param request
         * @param it
         * @param listener
         */
        private void receiveMessages(final FetchRequest request, final MessageIterator it,
                final MessageListener listener) {
            if (it != null && it.hasNext()) {
                if (this.processWhenRetryTooMany(request, it)) {
                    return;
                }
                final Partition partition = request.getPartitionObject();
                this.processReceiveMessage(request, it, listener, partition);
            }
            else {

                // 尝试多次无法解析出获取的数据，可能需要增大maxSize
                if (SimpleFetchManager.this.isRetryTooManyForIncrease(request) && it != null
                        && it.getDataLength() > 0) {
                    request.increaseMaxSize();
                    log.warn("警告，第" + request.getRetries() + "次无法拉取topic=" + request.getTopic() + ",partition="
                            + request.getPartition() + "的消息，递增maxSize=" + request.getMaxSize() + " Bytes");
                }

                // 一定要判断it是否为null,否则正常的拉到结尾时(返回null)也将进行Retries记数,会导致以后再拉到消息时进入recover
                if (it != null) {
                    request.incrementRetriesAndGet();
                }

                this.updateDelay(request);
                SimpleFetchManager.this.addFetchRequest(request);
            }
        }


        private void receiveMessages20(final FetchRequest request, final ListIterator<Message> it,
                final MessageListener listener) {
            if (it != null && it.hasNext()) {
                if (this.processWhenRetryTooMany20(request, it)) {
                    return;
                }
                final Partition partition = request.getPartitionObject();

                if (listener instanceof MessageListListener) {
                    this.processReceiveMessageList20(request, it, (MessageListListener) listener, partition);
                }
                else {
                    this.processReceiveMessage20(request, it, listener, partition);
                }
            }
            else {

                // 一定要判断it是否为null,否则正常的拉到结尾时(返回null)也将进行Retries记数,会导致以后再拉到消息时进入recover
                if (it != null) {
                    request.incrementRetriesAndGet();
                }

                this.updateDelay(request);
                SimpleFetchManager.this.addFetchRequest(request);
            }
        }


        /**
         * 返回是否需要跳过后续的处理
         * 
         * @param request
         * @param it
         * @param listener
         * @param partition
         * @return
         */
        private void processReceiveMessage(final FetchRequest request, final MessageIterator it,
                final MessageListener listener, final Partition partition) {
            int count = 0;
            while (it.hasNext()) {
                final int prevOffset = it.getOffset();
                partition.setOffset(request.getOffset());
                try {
                    final Message msg = it.next();

                    MessageAccessor.setPartition(msg, partition);
                    listener.recieveMessages(msg);

                    long newOffset = request.getOffset() + it.getOffset() - prevOffset;

                    if (partition.isAutoAck()) {
                        request.setOffset(newOffset, msg.getId(), true);
                        count++;
                    }
                    else {
                        // 消费者提交，将Offset存储到ZK
                        if (partition.isAcked()) {
                            partition.reset();

                            // 事务跨越多个消息情况
                            if (request.getTmpOffset() > 0) {
                                newOffset = newOffset + (request.getTmpOffset() - request.getOffset());
                            }

                            request.setOffset(newOffset, msg.getId(), true);
                            count++;
                        }
                        // 消费者回滚，清除从事务开始时前进的Offset
                        else if (partition.isRollback()) {
                            partition.reset();
                            request.rollbackOffset();
                            break;
                        }
                        // 不是提交也不是回滚，将Offset存储到内存临时变量
                        else {
                            request.setOffset(newOffset, msg.getId(), false);
                            count++;
                        }
                    }
                }
                catch (final InvalidMessageException e) {
                    MetaStatLog.addStat(null, StatConstants.INVALID_MSG_STAT, request.getTopic());
                    // 消息体非法，获取有效offset，重新发起查询
                    this.getOffsetAddRequest(request, e);
                    return;
                }
                catch (final Throwable e) {
                    // 将指针移到上一条消息
                    it.setOffset(prevOffset);
                    log.error(
                        "MessageListener处理消息异常,topic=" + request.getTopic() + ",partition="
                                + request.getPartition(), e);
                    // 跳出循环，处理消息异常，到此为止
                    break;
                }
            }

            // 如果offset仍然没有前进，递增重试次数
            if (it.getOffset() == 0) {
                request.incrementRetriesAndGet();
            }
            else {
                request.resetRetries();
            }

            this.addRequst(request);

            MetaStatLog.addStatValue2(null, StatConstants.GET_MSG_COUNT_STAT, request.getTopic(), count);
        }


        private void processReceiveMessage20(final FetchRequest request, final ListIterator<Message> it,
                final MessageListener listener, final Partition partition) {
            int count = 0;
            while (it.hasNext()) {
                partition.setOffset(request.getOffset());
                try {
                    final Message msg = it.next();

                    MessageAccessor.setPartition(msg, partition);
                    listener.recieveMessages(msg);

                    if (partition.isAutoAck()) {
                        request.setOffset(msg.getOffset() + 1, msg.getId(), true);
                        count++;
                    }
                    else {
                        // 消费者提交，将Offset存储到ZK
                        if (partition.isAcked()) {
                            partition.reset();
                            request.setOffset(msg.getOffset() + 1, msg.getId(), true);
                            count++;
                        }
                        // 消费者回滚，清除从事务开始时前进的Offset
                        else if (partition.isRollback()) {
                            partition.reset();
                            request.rollbackOffset();
                            break;
                        }
                        // 不是提交也不是回滚，将Offset存储到内存临时变量
                        else {
                            request.setOffset(msg.getOffset() + 1, msg.getId(), false);
                            count++;
                        }
                    }
                }
                catch (final Throwable e) {
                    // 将指针移到上一条消息
                    it.previous();
                    log.error(
                        "MessageListener处理消息异常,topic=" + request.getTopic() + ",partition="
                                + request.getPartition(), e);
                    // 跳出循环，处理消息异常，到此为止
                    break;
                }
            }

            // 如果offset仍然没有前进，递增重试次数
            if (count == 0) {
                request.incrementRetriesAndGet();
            }
            else {
                request.resetRetries();
            }

            this.addRequst(request);

            MetaStatLog.addStatValue2(null, StatConstants.GET_MSG_COUNT_STAT, request.getTopic(), count);
        }


        private void processReceiveMessageList20(final FetchRequest request, final ListIterator<Message> it,
                final MessageListListener listener, final Partition partition) {
            int count = 0;

            List<Message> msgs = new ArrayList<Message>();

            while (it.hasNext()) {
                try {
                    final Message msg = it.next();
                    if (msg != null) {
                        partition.setOffset(request.getOffset());
                        MessageAccessor.setPartition(msg, partition);
                        msgs.add(msg);
                    }
                }
                catch (final Throwable e) {
                    log.error(
                        "MessageListListener处理消息异常,topic=" + request.getTopic() + ",partition="
                                + request.getPartition(), e);
                    // 跳出循环，处理消息异常，到此为止
                    break;
                }
            }

            if (!msgs.isEmpty()) {
                Message lastMessage = msgs.get(msgs.size() - 1);
                try {
                    listener.recieveMessageList(msgs);

                    if (partition.isAutoAck()) {
                        request.setOffset(lastMessage.getOffset() + 1, lastMessage.getId(), true);
                        count++;
                    }
                    else {
                        // 消费者提交，将Offset存储到ZK
                        if (partition.isAcked()) {
                            partition.reset();
                            request.setOffset(lastMessage.getOffset() + 1, lastMessage.getId(), true);
                            count++;
                        }
                        // 消费者回滚，清除从事务开始时前进的Offset
                        else if (partition.isRollback()) {
                            partition.reset();
                            request.rollbackOffset();
                        }
                        // 不是提交也不是回滚，将Offset存储到内存临时变量
                        else {
                            request.setOffset(lastMessage.getOffset() + 1, lastMessage.getId(), false);
                            count++;
                        }
                    }
                }
                catch (Throwable e) {
                    log.error("recieveMessageList throw exception.", e);
                }
            }

            // 如果offset仍然没有前进，递增重试次数
            if (count == 0) {
                request.incrementRetriesAndGet();
            }
            else {
                request.resetRetries();
            }

            this.addRequst(request);

            MetaStatLog.addStatValue2(null, StatConstants.GET_MSG_COUNT_STAT, request.getTopic(), count);
        }


        private boolean processWhenRetryTooMany(final FetchRequest request, final MessageIterator it) {
            if (SimpleFetchManager.this.isRetryTooMany(request)) {
                try {
                    final Message couldNotProecssMsg = it.next();
                    MessageAccessor.setPartition(couldNotProecssMsg, request.getPartitionObject());
                    MetaStatLog.addStat(null, StatConstants.SKIP_MSG_COUNT, couldNotProecssMsg.getTopic());
                    SimpleFetchManager.this.consumer.appendCouldNotProcessMessage(couldNotProecssMsg);
                }
                catch (final InvalidMessageException e) {
                    MetaStatLog.addStat(null, StatConstants.INVALID_MSG_STAT, request.getTopic());
                    // 消息体非法，获取有效offset，重新发起查询
                    this.getOffsetAddRequest(request, e);
                    return true;
                }
                catch (final Throwable t) {
                    this.LogAddRequest(request, t);
                    return true;
                }

                request.resetRetries();
                // 跳过这条不能处理的消息
                request.setOffset(request.getOffset() + it.getOffset(), it.getPrevMessage().getId(), true);
                // 强制设置延迟为0
                request.setDelay(0);
                SimpleFetchManager.this.addFetchRequest(request);
                return true;
            }
            else {
                return false;
            }
        }


        private boolean processWhenRetryTooMany20(final FetchRequest request, final MessageIterator it) {
            if (SimpleFetchManager.this.isRetryTooMany(request)) {
                try {
                    final Message couldNotProecssMsg = it.next();
                    MessageAccessor.setPartition(couldNotProecssMsg, request.getPartitionObject());
                    MetaStatLog.addStat(null, StatConstants.SKIP_MSG_COUNT, couldNotProecssMsg.getTopic());
                    SimpleFetchManager.this.consumer.appendCouldNotProcessMessage(couldNotProecssMsg);
                }
                catch (final InvalidMessageException e) {
                    MetaStatLog.addStat(null, StatConstants.INVALID_MSG_STAT, request.getTopic());
                    // 消息体非法，获取有效offset，重新发起查询
                    this.getOffsetAddRequest(request, e);
                    return true;
                }
                catch (final Throwable t) {
                    this.LogAddRequest(request, t);
                    return true;
                }

                request.resetRetries();
                // 跳过这条不能处理的消息
                request.setOffset(request.getOffset() + it.getOffset(), it.getPrevMessage().getId(), true);
                // 强制设置延迟为0
                request.setDelay(0);
                SimpleFetchManager.this.addFetchRequest(request);
                return true;
            }
            else {
                return false;
            }
        }


        private boolean processWhenRetryTooMany20(final FetchRequest request, final ListIterator<Message> it) {
            if (SimpleFetchManager.this.isRetryTooMany(request)) {
                long id = 100;
                try {
                    final Message couldNotProecssMsg = it.next();
                    id = couldNotProecssMsg.getId();
                    MessageAccessor.setPartition(couldNotProecssMsg, request.getPartitionObject());
                    MetaStatLog.addStat(null, StatConstants.SKIP_MSG_COUNT, couldNotProecssMsg.getTopic());
                    SimpleFetchManager.this.consumer.appendCouldNotProcessMessage(couldNotProecssMsg);
                }
                catch (final Throwable t) {
                    this.LogAddRequest(request, t);
                    return true;
                }

                request.resetRetries();
                // 跳过这条不能处理的消息
                request.setOffset(request.getOffset() + 1, id, true);
                // 强制设置延迟为0
                request.setDelay(0);
                SimpleFetchManager.this.addFetchRequest(request);
                return true;
            }
            else {
                return false;
            }
        }


        private void ackRequest(final FetchRequest request, final MessageIterator it, final boolean ack) {
            request.setOffset(request.getOffset() + it.getOffset(), it.getPrevMessage() != null ? it
                .getPrevMessage().getId() : -1, ack);
            this.addRequst(request);
        }


        private void addRequst(final FetchRequest request) {
            final long delay = this.getRetryDelay(request);
            request.setDelay(delay);
            SimpleFetchManager.this.addFetchRequest(request);
        }


        private long getRetryDelay(final FetchRequest request) {
            final long maxDelayFetchTimeInMills = SimpleFetchManager.this.getMaxDelayFetchTimeInMills();
            final long nPartsDelayTime = maxDelayFetchTimeInMills / DELAY_NPARTS;
            // 延迟时间为：最大延迟时间/10*重试次数
            long delay = nPartsDelayTime * request.getRetries();
            if (delay > maxDelayFetchTimeInMills) {
                delay = maxDelayFetchTimeInMills;
            }
            return delay;
        }


        // 延时查询
        private void updateDelay(final FetchRequest request) {
            final long delay = this.getNextDelay(request);
            request.setDelay(delay);
        }


        private long getNextDelay(final FetchRequest request) {
            final long maxDelayFetchTimeInMills = SimpleFetchManager.this.getMaxDelayFetchTimeInMills();
            // 每次1/10递增,最大MaxDelayFetchTimeInMills
            final long nPartsDelayTime = maxDelayFetchTimeInMills / DELAY_NPARTS;
            long delay = request.getDelay() + nPartsDelayTime;
            if (delay > maxDelayFetchTimeInMills) {
                delay = maxDelayFetchTimeInMills;
            }
            return delay;
        }

    }


    boolean isRetryTooMany(final FetchRequest request) {
        return request.getRetries() > this.consumerConfig.getMaxFetchRetries();
    }


    boolean isRetryTooManyForIncrease(final FetchRequest request) {
        return request.getRetries() > this.consumerConfig.getMaxIncreaseFetchDataRetries();
    }


    long getMaxDelayFetchTimeInMills() {
        return this.consumerConfig.getMaxDelayFetchTimeInMills();
    }

}
