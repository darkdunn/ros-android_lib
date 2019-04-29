package org.ros.android.android_tutorial_pubsub;

import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ros.concurrent.DefaultScheduledExecutorService;
import org.ros.namespace.GraphName;
import org.ros.node.ConnectedNode;
import org.ros.node.DefaultNodeFactory;
import org.ros.node.Node;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeFactory;
import org.ros.node.NodeListener;
import org.ros.node.NodeMain;
import org.ros.node.NodeMainExecutor;

public class RoboosterNodeMainExecutor implements NodeMainExecutor {
    private static final boolean DEBUG = false;
    private static final Log log = LogFactory.getLog(RoboosterNodeMainExecutor.class);
    private final NodeFactory nodeFactory;
    private final ScheduledExecutorService scheduledExecutorService;
    private final Multimap<GraphName, ConnectedNode> connectedNodes;
    private final BiMap<Node, NodeMain> nodeMains;

    public static NodeMainExecutor newDefault() {
        return newDefault(new DefaultScheduledExecutorService());
    }

    public static NodeMainExecutor newDefault(ScheduledExecutorService executorService) {
        return new RoboosterNodeMainExecutor(new DefaultNodeFactory(executorService), executorService);
    }

    private RoboosterNodeMainExecutor(NodeFactory nodeFactory, ScheduledExecutorService scheduledExecutorService) {
        this.nodeFactory = nodeFactory;
        this.scheduledExecutorService = scheduledExecutorService;
        this.connectedNodes = Multimaps.synchronizedMultimap(HashMultimap.<GraphName, ConnectedNode>create());
        this.nodeMains = Maps.synchronizedBiMap(HashBiMap.<Node, NodeMain>create());
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                RoboosterNodeMainExecutor.this.shutdown();
            }
        }));
    }

    public ScheduledExecutorService getScheduledExecutorService() {
        return this.scheduledExecutorService;
    }

    public void execute(final NodeMain nodeMain, NodeConfiguration nodeConfiguration, final Collection<NodeListener> nodeListeners) {
        final NodeConfiguration nodeConfigurationCopy = NodeConfiguration.copyOf(nodeConfiguration);
        nodeConfigurationCopy.setDefaultNodeName(nodeMain.getDefaultNodeName());
        Preconditions.checkNotNull(nodeConfigurationCopy.getNodeName(), "Node name not specified.");
        this.scheduledExecutorService.execute(new Runnable() {
            public void run() {
                Collection<NodeListener> nodeListenersCopy = Lists.newArrayList();
                nodeListenersCopy.add(RoboosterNodeMainExecutor.this.new RegistrationListener());
                nodeListenersCopy.add(nodeMain);
                if (nodeListeners != null) {
                    nodeListenersCopy.addAll(nodeListeners);
                }

                Node node = RoboosterNodeMainExecutor.this.nodeFactory.newNode(nodeConfigurationCopy, nodeListenersCopy);
                RoboosterNodeMainExecutor.this.nodeMains.put(node, nodeMain);
            }
        });
    }

    public void execute(NodeMain nodeMain, NodeConfiguration nodeConfiguration) {
        this.execute(nodeMain, nodeConfiguration, (Collection)null);
    }

    public void shutdownNodeMain(NodeMain nodeMain) {
        Node node = (Node)this.nodeMains.inverse().get(nodeMain);
        if (node != null) {
            this.safelyShutdownNode(node);
        }

    }

    public void shutdown() {
        synchronized(this.connectedNodes) {
            Iterator var2 = this.connectedNodes.values().iterator();

            while(var2.hasNext()) {
                ConnectedNode connectedNode = (ConnectedNode)var2.next();
                this.safelyShutdownNode(connectedNode);
            }

        }
    }

    private void safelyShutdownNode(Node node) {
        boolean success = true;

        try {
            node.shutdown();
        } catch (Exception var4) {
            log.error("Exception thrown while shutting down node.", var4);
            this.unregisterNode(node);
            success = false;
        }

        if (success) {
            log.info("Shutdown successful.");
        }

    }

    private void registerNode(ConnectedNode connectedNode) {
        GraphName nodeName = connectedNode.getName();
        synchronized(this.connectedNodes) {
            Iterator var4 = this.connectedNodes.get(nodeName).iterator();

            while(var4.hasNext()) {
                ConnectedNode illegalConnectedNode = (ConnectedNode)var4.next();
                System.err.println(String.format("Node name collision. Existing node %s (%s) will be shutdown.", nodeName, illegalConnectedNode.getUri()));
                illegalConnectedNode.shutdown();
            }

            this.connectedNodes.put(nodeName, connectedNode);
        }
    }

    private void unregisterNode(Node node) {
        node.removeListeners();
        this.connectedNodes.get(node.getName()).remove(node);
        this.nodeMains.remove(node);
    }

    private class RegistrationListener implements NodeListener {
        private RegistrationListener() {
        }

        public void onStart(ConnectedNode connectedNode) {
            RoboosterNodeMainExecutor.this.registerNode(connectedNode);
        }

        public void onShutdown(Node node) {
        }

        public void onShutdownComplete(Node node) {
            RoboosterNodeMainExecutor.this.unregisterNode(node);
        }

        public void onError(Node node, Throwable throwable) {
            RoboosterNodeMainExecutor.log.error("Node error.", throwable);
            RoboosterNodeMainExecutor.this.unregisterNode(node);
        }
    }
}
