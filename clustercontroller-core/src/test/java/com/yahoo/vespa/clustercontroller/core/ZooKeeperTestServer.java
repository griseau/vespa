// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core;

import com.yahoo.net.HostName;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * This class sets up a zookeeper server, such that we can test fleetcontroller zookeeper parts without stubbing in the client.
 */
public class ZooKeeperTestServer {
    private File zooKeeperDir;
    private ZooKeeperServer server;
    private static final int tickTime = 100;
    private NIOServerCnxnFactory factory;
    private static final String DIR_PREFIX = "test_fltctrl_zk";
    private static final String DIR_POSTFIX = "sdir";

    ZooKeeperTestServer() throws IOException {
        this(0);
    }

    private ZooKeeperTestServer(int port) throws IOException {
        zooKeeperDir = getTempDir();
        delete(zooKeeperDir);
        if (!zooKeeperDir.mkdir()) {
            throw new IllegalStateException("Failed to create directory " + zooKeeperDir);
        }
        zooKeeperDir.deleteOnExit();
        server = new ZooKeeperServer(zooKeeperDir, zooKeeperDir, tickTime);
        final int maxcc = 10000; // max number of connections from the same client
        factory = new NIOServerCnxnFactory();
        factory.configure(new InetSocketAddress(port), maxcc); // Use any port
        try{
            factory.startup(server);
        } catch (InterruptedException e) {
            throw (RuntimeException) new IllegalStateException("Interrupted during test startup: ").initCause(e);
        }
    }

    static ZooKeeperTestServer createWithFixedPort(int port) throws IOException {
        return new ZooKeeperTestServer(port);
    }

    private int getPort() {
        return factory.getLocalPort();
    }

    String getAddress() {
        return HostName.getLocalhost() + ":" + getPort();
    }

    public void shutdown(boolean cleanupZooKeeperDir) {
        server.shutdown();

        if (cleanupZooKeeperDir) {
            delete(zooKeeperDir);
        }

        factory.shutdown();
    }

    private void delete(File f) {
        if (f.isDirectory()) {
            for (File file : f.listFiles()) {
                delete(file);
            }
        }
        f.delete();
    }

    private static File getTempDir() throws IOException {
        // The pom file sets java.io.tmpdir to ${project.build.directory}. This doesn't happen within (e.g.) IntelliJ, but happens
        // on Screwdriver (tm). So if we're running tests on Screwdriver (tm), put the log in 'surefire-reports' instead so the
        // user can find them along with the other test reports.
        final File surefireReportsDir = new File(System.getProperty("java.io.tmpdir") + File.separator + "surefire-reports");
        if (surefireReportsDir.isDirectory()) {
            return File.createTempFile(DIR_PREFIX, DIR_POSTFIX, surefireReportsDir);
        }

        return File.createTempFile(DIR_PREFIX, DIR_POSTFIX);
    }
}
