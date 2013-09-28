package com.taobao.metamorphosis.tools.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.Session;
import ch.ethz.ssh2.StreamGobbler;


/**
 * 
 * @author shuihan
 * @date 2011-5-18
 **/
public class SSHSupport {
    private final static Log log = LogFactory.getLog(SSHSupport.class);
    private String user = "nobody";

    private String password = "look";

    private String ip = "127.0.0.1";


    private SSHSupport(String user, String password, String ip) {
        this.user = user;
        this.password = password;
        this.ip = ip;
    }


    private SSHSupport(String ip) {
        this.ip = ip;
    }


    public static SSHSupport newInstance(String user, String password, String ip) {
        return new SSHSupport(user, password, ip);
    }


    public static SSHSupport newInstance(String ip) {
        return new SSHSupport(ip);
    }


    public String execute(String cmd) throws RemoteExecuteException {
        StringBuilder result = new StringBuilder();
        try {
            Connection conn = new Connection(this.ip);
            conn.connect();
            boolean isAuthenticated = conn.authenticateWithPassword(this.user, this.password);
            if (isAuthenticated == false) {
                result.append("ERROR: Authentication Failed !");
            }

            Session session = conn.openSession();

            session.execCommand(cmd);
            BufferedReader read =
                    new BufferedReader(new InputStreamReader(new StreamGobbler(session.getStdout()), "GBK"));
            String line = "";
            while ((line = read.readLine()) != null) {
                result.append(line).append("\r\n");
            }
            session.close();
            conn.close();
            return result.toString();
        }
        catch (Throwable e) {
            throw new RemoteExecuteException("ִ���������", e);
        }
    }


    public static void main(String[] args) {
        /*
         * SSHSupport ssh = SSHSupport.newInstance("shuihan", "panxianjin0",
         * "10.232.37.120"); System.out.println(ssh.execute(
         * "./scp.expt 10.232.10.36 /home/shuihan/tomcat-6.0.20/webapps/notify-console-3.0-SNAPSHOT/WEB-INF/classes/projectInfoConfig.xml /home/notify/ notify tjjtds"
         * ));
         */
        SSHSupport ssh = SSHSupport.newInstance("notify", "tjjtds", "10.232.10.36");
        System.out.println(ssh.execute("/home/notify/bin/reload.sh notifyhost"));

    }

}
