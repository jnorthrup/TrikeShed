package rxf.server;

import rxf.server.BlobAntiPatternObject;
import rxf.server.Rfc822HeaderState;

import static java.nio.channels.SelectionKey.OP_CONNECT;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;
import static one.xio.HttpMethod.UTF8;
import static rxf.server.driver.RxfBootstrap.getVar;

/**
 * <ul>
 * <li> Accepts external socket connections on behalf of Couchdb or other REST server
 * <p/>
 * </ul>
 *
 * User: jnorthrup
 * Date: 10/1/13
 * Time: 7:26 PM
 */
public class ProxyDaemon extends one.xio.AsioVisitor.Impl {
  /**
   * until proven otherwise, all http requests must conform to crlf line-endings, and it is the primary termination token we are seeking in bytebuffer operations.
   */
  public static final byte[] TERMINATOR = new byte[] {'\r', '\n', '\r', '\n'};
  /**
   * a shortcut to locating the Host header uses this length
   */
  public static final int HOSTPREFIXLEN = "Host: ".length();

  public static final int PROXY_PORT = Integer.parseInt(getVar("PROXY_PORT", "0"));
  public static final String PROXY_HOST = getVar("PROXY_HOST", "127.0.0.1");
  private static final boolean RPS_SHOW = "true".equals(getVar("RPS_SHOW", "true"));
  private static final boolean PROXY_DEBUG = "true".equals(getVar("PROXY_DEBUG", "false"));
  /**
   * master counter for stats on inbound requests
   */
  public static int counter = 0;
  public java.nio.channels.FileChannel hdrStream;
  /**
   * request lead-in data is placed in this buffer.
   */
  java.nio.ByteBuffer cursor;

  private ProxyTask proxyTask;

  private java.net.InetSocketAddress preallocAddr;

  public ProxyDaemon(ProxyTask... proxyTask) {
    this.proxyTask = proxyTask.length > 0 ? proxyTask[0] : new ProxyTask();

    if (PROXY_PORT != 0)
      try {
        preallocAddr = new java.net.InetSocketAddress(java.net.InetAddress.getByName(PROXY_HOST), PROXY_PORT);
      } catch (java.net.UnknownHostException e) {
        e.printStackTrace();
      }
  }

  /**
   * creates a http-specific socket proxy to move bytes between innerKey and outerKey in the async framework.
   *
   * @param outerKey connection to the f5
   * @param innerKey connection to the Distributor
   * @param b        the DMA ByteBuffers where applicable
   */
  public static void pipe(java.nio.channels.SelectionKey innerKey, final java.nio.channels.SelectionKey outerKey, final java.nio.ByteBuffer... b) {
    String s = "pipe-" + counter;
    final java.nio.ByteBuffer ob = b.length > 1 ? b[1] : java.nio.ByteBuffer.allocate(4 << 10);
    final HttpPipeVisitor ib = new HttpPipeVisitor(s + "-in", innerKey, b[0], ob);
    outerKey.interestOps(OP_READ | OP_WRITE).attach(ib);
    innerKey.interestOps(OP_WRITE);
    innerKey.attach(new HttpPipeVisitor(s + "-out", outerKey, ob, b[0]) {
      public boolean fail;

      @Override
      public void onRead(java.nio.channels.SelectionKey key) throws Exception {
        if (!ib.isLimit() || fail) {
          java.nio.channels.SocketChannel channel = (java.nio.channels.SocketChannel) key.channel();
          int read = channel.read(getInBuffer());
          switch (read) {
            case -1:
              channel.close();
            case 0:
              return;
            default:
              Rfc822HeaderState.HttpResponse httpResponse =
                  new Rfc822HeaderState().headerInterest(one.xio.HttpHeaders.Content$2dLength).apply(
                      (java.nio.ByteBuffer) getInBuffer().duplicate().flip()).$res();
              //              if (BlobAntiPatternObject.suffixMatchChunks(TERMINATOR, httpResponse.headerBuf()
              //                                .duplicate())) ;
              break;
          }
        }
        super.onRead(key);
      }
    });
  }

  @Override
  public void onAccept(java.nio.channels.SelectionKey key) throws Exception {
    java.nio.channels.ServerSocketChannel c = (java.nio.channels.ServerSocketChannel) key.channel();
    final java.nio.channels.SocketChannel accept = c.accept();
    accept.configureBlocking(false);
    one.xio.HttpMethod.enqueue(accept, OP_READ, this);
  }

  @Override
  public void onRead(final java.nio.channels.SelectionKey outerKey) throws Exception {

    if (cursor == null)
      cursor = java.nio.ByteBuffer.allocate(4 << 10);
    final java.nio.channels.SocketChannel outterChannel = (java.nio.channels.SocketChannel) outerKey.channel();
    int read = outterChannel.read(cursor);
    if (-1 != read) {
      boolean timeHeaders = RPS_SHOW && counter % 1000 == 0;
      long l = 0;

      if (timeHeaders)
        l = System.nanoTime();
      Rfc822HeaderState.HttpRequest req =
          (Rfc822HeaderState.HttpRequest) new Rfc822HeaderState().$req().headerInterest(
              one.xio.HttpHeaders.Host).apply((java.nio.ByteBuffer) cursor.duplicate().flip());
      java.nio.ByteBuffer headersBuf = req.headerBuf();
      if (BlobAntiPatternObject.suffixMatchChunks(TERMINATOR, headersBuf)) {

        int climit = cursor.position();
        if (PROXY_DEBUG) {
          String decode = UTF8.decode((java.nio.ByteBuffer) headersBuf.duplicate().rewind()).toString();
          String[] split = decode.split("[\r\n]+");
          System.err.println(java.util.Arrays.deepToString(split));
        }
        req.headerString(one.xio.HttpHeaders.Host, proxyTask.prefix);
        java.net.InetSocketAddress address =
            (java.net.InetSocketAddress) outterChannel.socket().getRemoteSocketAddress();

        //grab a frame of int offsets
        java.util.Map<String, int[]> headers = one.xio.HttpHeaders.getHeaders((java.nio.ByteBuffer) headersBuf.flip());
        int[] hosts = headers.get("Host");

        java.nio.ByteBuffer slice2 =
            UTF8.encode("Host: " + proxyTask.prefix + "\r\nX-Origin-Host: " + address.toString()
                + "\r\n");

        java.nio.Buffer position = cursor.limit(climit).position(headersBuf.limit());

        final java.nio.ByteBuffer inwardBuffer =
            java.nio.ByteBuffer.allocateDirect(8 << 10).put(
                (java.nio.ByteBuffer) cursor.clear().limit(1 + hosts[0] - HOSTPREFIXLEN)).put(
                (java.nio.ByteBuffer) cursor.limit(headersBuf.limit() - 2).position(hosts[1])).put(slice2)
                .put((java.nio.ByteBuffer) position);
        cursor = null;

        if (PROXY_DEBUG) {
          java.nio.ByteBuffer flip = (java.nio.ByteBuffer) inwardBuffer.duplicate().flip();
          System.err.println(UTF8.decode(flip).toString() + "-");
          if (timeHeaders)
            System.err.println("header decode (ns):" + (System.nanoTime() - l));
        }
        counter++;

        final java.nio.channels.SocketChannel innerChannel =
            (java.nio.channels.SocketChannel) java.nio.channels.SocketChannel.open().configureBlocking(false);
        java.net.InetSocketAddress remote;
        switch (PROXY_PORT) {
          case 0:
            java.net.InetSocketAddress localSocketAddress =
                (java.net.InetSocketAddress) ((java.nio.channels.SocketChannel) outerKey.channel()).socket()
                    .getLocalSocketAddress();
            remote =
                new java.net.InetSocketAddress(java.net.InetAddress.getByName(PROXY_HOST), localSocketAddress
                    .getPort());
            break;
          default:
            remote = preallocAddr;
            break;
        }
        innerChannel.connect(remote);
        innerChannel.register(outerKey.selector().wakeup(), OP_CONNECT, new one.xio.AsioVisitor.Impl() {
          @Override
          public void onConnect(java.nio.channels.SelectionKey key) throws Exception {
            if (innerChannel.finishConnect())
              pipe(key, outerKey, inwardBuffer, (java.nio.ByteBuffer) java.nio.ByteBuffer.allocateDirect(8 << 10)
                  .clear());
          }
        });
      }
    } else
      outerKey.cancel();
  }

  @Override
  public void onWrite(java.nio.channels.SelectionKey key) throws Exception {
    super.onWrite(key); //To change body of overridden methods use File | Settings | File Templates.
  }

}
