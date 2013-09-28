package com.taobao.metamorphosis.server.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.easymock.classextension.EasyMock;
import org.junit.Test;

import com.taobao.gecko.service.Connection;
import com.taobao.metamorphosis.server.utils.XIDGenerator;
import com.taobao.metamorphosis.transaction.LocalTransactionId;
import com.taobao.metamorphosis.transaction.XATransactionId;


public class SessionContextHolderUnitTest {

    @Test
    public void testGetOrCreateSessionContext_LocalTransactionId() {
        final Connection conn = EasyMock.createMock(Connection.class);
        final LocalTransactionId xid = new LocalTransactionId("test", 1);
        EasyMock.expect(conn.getAttribute("test")).andReturn(null);
        EasyMock.expect(conn.setAttributeIfAbsent("test", new SessionContextImpl("test", conn))).andReturn(null);
        EasyMock.replay(conn);

        final SessionContext ctx = SessionContextHolder.getOrCreateSessionContext(conn, xid);
        assertNotNull(ctx);
        assertEquals("test", ctx.getSessionId());
        assertSame(conn, ctx.getConnection());
        assertTrue(ctx.getTransactions().isEmpty());
        assertFalse(ctx.isInRecoverMode());
        EasyMock.verify(conn);
    }


    @Test
    public void testGetOrCreateSessionContext_XATransactionId() {
        final Connection conn = EasyMock.createMock(Connection.class);
        final XATransactionId xid = XIDGenerator.createXID(0);
        EasyMock.expect(conn.getAttribute(SessionContextHolder.GLOBAL_SESSION_KEY)).andReturn(null);
        EasyMock.expect(
            conn.setAttributeIfAbsent(SessionContextHolder.GLOBAL_SESSION_KEY, new SessionContextImpl(null, conn)))
            .andReturn(null);
        EasyMock.replay(conn);

        final SessionContext ctx = SessionContextHolder.getOrCreateSessionContext(conn, xid);
        assertNotNull(ctx);
        assertNull(ctx.getSessionId());
        assertSame(conn, ctx.getConnection());
        assertTrue(ctx.getTransactions().isEmpty());
        assertFalse(ctx.isInRecoverMode());
        EasyMock.verify(conn);
    }
}
