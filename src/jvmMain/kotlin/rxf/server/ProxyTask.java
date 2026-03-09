package rxf.server;

import rxf.server.BlobAntiPatternObject;

/**
 * this launches the main service thread and assigns the proxy port to socketservers.
 * User: jnorthrup
 * Date: 10/1/13
 * Time: 7:27 PM
 */
public class ProxyTask implements Runnable {
  public String prefix;
  public String[] proxyPorts;

  @Override
  public void run() {
    try {
      for (String proxyPort : proxyPorts) {
        one.xio.HttpMethod.enqueue(java.nio.channels.ServerSocketChannel.open().bind(
            new java.net.InetSocketAddress(Integer.parseInt(proxyPort)), 4096).setOption(
            java.net.StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE).configureBlocking(false),
            java.nio.channels.SelectionKey.OP_ACCEPT, new ProxyDaemon(this));
      }

    } catch (Throwable e) {
      e.printStackTrace();
    }
  }

  public static void main(final String[] args) {
    //boilerplate HttpMethod.init() here
    BlobAntiPatternObject.getEXECUTOR_SERVICE().submit(new rxf.server.ProxyTask() {
      {
        proxyPorts = args;
      }
    });
  }

}
