/*
 * Created on 4 juil. 2003
 *
 */
package org.gudy.azureus2.core2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import org.gudy.azureus2.core.ByteBufferPool;
import org.gudy.azureus2.core.Constants;
import org.gudy.azureus2.core.Logger;
import org.gudy.azureus2.core.MessageText;
import org.gudy.azureus2.core.PeerManager;
import org.gudy.azureus2.core.Request;
import org.gudy.azureus2.core.SpeedLimiter;

/**
 * @author Olivier
 *
 */
public class PeerSocket extends PeerConnection {

  /*
     * This object constructors will let the PeerConnection partially created,
     * but hopefully will let us gain some memory for peers not going to be
     * accepted.
     */

  /**
   * The Default Contructor for outgoing connections.
   * @param manager the manager that will handle this PeerConnection
   * @param table the graphical table in which this PeerConnection should display its info
   * @param peerId the other's peerId which will be checked during Handshaking
   * @param ip the peer Ip Address
   * @param port the peer port
   */
  public PeerSocket(PeerManager manager, byte[] peerId, String ip, int port, boolean fake) {
    super(manager, peerId, ip, port);
    if (fake) {
      this.fake = true;
      return;
    }
    initialize();
  }

  private void initialize() {
    this.fake = false;
    this.incoming = false;
    allocateAll();
//    logger.log(componentID, evtLifeCycle, Logger.INFORMATION, "Creating outgoing connection to " + ip + " : " + port);

    try {
      socket = SocketChannel.open();
      socket.configureBlocking(false);
      socket.connect(new InetSocketAddress(ip, port));
      this.currentState = new StateConnecting();
    }
    catch (Exception e) {
      e.printStackTrace();
      closeAll();
    }
  }

  /**
   * The default Contructor for incoming connections
   * @param manager the manager that will handle this PeerConnection
   * @param table the graphical table in which this PeerConnection should display its info
   * @param sck the SocketChannel that handles the connection
   */
  public PeerSocket(PeerManager manager, SocketChannel sck) {
    super(manager, sck.socket().getInetAddress().getHostAddress(), sck.socket().getPort());
    this.socket = sck;
    this.incoming = true;
    allocateAll();
    logger.log(componentID, evtLifeCycle, Logger.INFORMATION, "Creating incoming connection from " + ip + " : " + port);
    handShake();
    this.currentState = new StateHandshaking();
  }

  /**
   * Private method that will finish fields allocation, once the handshaking is ok.
   * Hopefully, that will save some RAM.
   */
  protected void allocateAll() {

    super.allocateAll();

    this.closing = false;
    this.logger = Logger.getLogger();
    this.protocolQueue = new ArrayList();
    this.dataQueue = new ArrayList();
    this.lengthBuffer = ByteBuffer.allocate(4);

    this.allowed = 0;
    this.used = 0;
    this.loopFactor = 0;
  }

  protected void handShake() {
    try {
      byte[] protocol = PROTOCOL.getBytes();
      byte[] reserved = new byte[] { 0, 0, 0, 0, 0, 0, 0, 0 };
      byte[] hash = manager.getHash();
      byte[] myPeerId = manager.getPeerId();

      ByteBuffer bufferHandshakeS = ByteBuffer.allocate(68);
      bufferHandshakeS.put((byte) PROTOCOL.length()).put(protocol).put(reserved).put(hash).put(myPeerId);

      bufferHandshakeS.position(0);
      bufferHandshakeS.limit(68);
      sendProtocol(bufferHandshakeS);
      readBuffer = ByteBufferPool.getInstance().getFreeBuffer();
      readBuffer.limit(68);
      readBuffer.position(0);
    }
    catch (Exception e) {
      closeAll();
    }
  }

  protected void handleHandShakeResponse() {
    boolean bContinue = true;
    readBuffer.position(0);
    //Now test for data...

    if (readBuffer.get() != (byte) PROTOCOL.length())
      bContinue = false;

    byte[] protocol = PROTOCOL.getBytes();
    readBuffer.get(protocol);
    if (!(new String(protocol)).equals(PROTOCOL))
      bContinue = false;

    byte[] reserved = new byte[8];
    readBuffer.get(reserved);
    //Ignores reserved bytes

    byte[] hash = manager.getHash();

    byte[] otherHash = new byte[20];
    readBuffer.get(otherHash);
    for (int i = 0; i < 20; i++) {
      if (otherHash[i] != hash[i])
        bContinue = false;
    }

    byte[] otherPeerId = new byte[20];
    readBuffer.get(otherPeerId);

    if (bContinue && incoming) {
      //HandShaking is ok so far
      //We test if the handshaking is valid (no other connections with that peer)
      // TODO restore the call to validation ...
      if (manager.validateHandshaking(this, otherPeerId)) {
        //Store the peerId
        this.id = otherPeerId;
      }
      else
        bContinue = false;
    }

    if (bContinue && !incoming) {
      boolean same = true;
      for (int j = 0; j < this.id.length; j++)
        same = same && (this.id[j] == otherPeerId[j]);

      if (!same)
        bContinue = false;
    }

    try {
      client = MessageText.getString("PeerSocket.generic"); //$NON-NLS-1$
      String xan = new String(otherPeerId, 0, 11, Constants.BYTE_ENCODING);
      if (xan.equals("DansClient "))
        client = "Xan'";
      String azureus = new String(otherPeerId, 5, 7, Constants.BYTE_ENCODING);
      if (azureus.equals("Azureus"))
        client = "Azureus";
    }
    catch (Exception e) {}

    if (!bContinue)
      closeAll();
    else {
      sendBitField();
      readMessage(readBuffer);
      manager.peerAdded(this);
      currentState = new StateTransfering();
    }
  }

  protected void readMessage(ByteBuffer buffer) {
    lengthBuffer.position(0);
    buffer.position(0);
    readBuffer = buffer;
    readingLength = true;
  }

  protected void sendProtocol(ByteBuffer buffer) {
    protocolQueue.add(buffer);
  }

  protected void sendData(Request request) {
    dataQueue.add(new DataQueueItem(request));
  }

  public synchronized void closeAll() {
    if (closing)
      return;
    closing = true;
    this.currentState = new StateClosed();

    //1. Remove us from the manager, so it will stop asking us for things :p
    manager.removePeer(this);

    //2. Cancel any pending requests (on the manager side)
    cancelRequests();

    //3. Close the socket
    if (socket != null) {
      try {
        socket.close();
      } catch (Exception e) {
        e.printStackTrace(); // logger.log(componentID, evtErrors, Logger.ERROR, "Error in PeerConnection::closeAll-sck.close() (" + ip + " : " + port + " ) : " + e);
      }
      socket = null;
    }

    //4. release the read Buffer
    if (readBuffer != null)
      ByteBufferPool.getInstance().freeBuffer(readBuffer);

    //5. release the write Buffer
    if (writeBuffer != null) {
      ByteBufferPool.getInstance().freeBuffer(writeBuffer);
      if (writeData) {
        SpeedLimiter.getLimiter().removeUploader(this);
      }
    }

    //6. release all buffers in dataQueue
    for (int i = dataQueue.size() - 1; i >= 0; i--) {
      DataQueueItem item = (DataQueueItem) dataQueue.remove(i);
      if (item.isLoaded()) {
        ByteBufferPool.getInstance().freeBuffer(item.getBuffer());
      }
      else if (item.isLoading()) {
        manager.freeRequest(item);
      }
    }

    //7. Send removed event ...
    manager.peerRemoved(this);

    //8. Send a logger event
//    logger.log(componentID, evtLifeCycle, Logger.INFORMATION, "Connection Ended with " + ip + " : " + port);
  }

  private class PeerUpdater extends Thread {
    public PeerUpdater() {
      super("Peer Updater"); //$NON-NLS-1$
      setPriority(Thread.NORM_PRIORITY); // incoming ? Thread.MAX_PRIORITY : Thread.NORM_PRIORITY
    }

    public void run() {
      while (!closing) {
        loopFactor++;
        if (currentState != null)
          currentState.process();
        write();
        if(incoming) {
          try {
            Thread.sleep(30);
          } catch (InterruptedException ignore) {
          }
        }
      }
    }
  }

  private class StateConnecting implements State {
    public void process() {
      try {
        if(socket.finishConnect()) {
        handShake();
        currentState = new StateHandshaking();
        }
      }
      catch (IOException e) {
        logger.log(
          componentID,
          evtErrors,
          Logger.ERROR,
          "Error in PeerConnection::initConnection (" + ip + " : " + port + " ) : " + e);
        closeAll();
        return;
      }

    }

    public int getState() {
      return CONNECTING;
    }
  }

  private class StateHandshaking implements State {
    public void process() {
      if (readBuffer.hasRemaining()) {
        try {
          int read = -1;
          socket.finishConnect();
          read = socket.read(readBuffer);
          if (read < 0) {
            closeAll();
            return;
          }
        } catch (Exception e) {
          closeAll();
          return;
        }
      }
      if (readBuffer.remaining() == 0) {
        handleHandShakeResponse();
      }
    }

    public int getState() {
      return HANDSHAKING;
    }
  }

  private class StateTransfering implements State {
    public void process() {
      if (readingLength) {
        if (lengthBuffer.hasRemaining()) {
          int read = processSocket(lengthBuffer, true);
          if (read < 0) {
            closeAll();
            return;
          }
        }
        if (!lengthBuffer.hasRemaining()) {
          int length = lengthBuffer.getInt(0);
          if (length > 0) {
            readBuffer.limit(lengthBuffer.getInt(0));
            readingLength = false;
          }
          else {
            readMessage(readBuffer);
          }
        }
      }
      if (!readingLength) {
        int read = processSocket(readBuffer, true);
        if (read < 0) {
          closeAll();
          return;
        }
        //hack to statistically determine when we're receving data...
        if (readBuffer.limit() > 8192) {
          stats.received(read);
          manager.received(read);
        }
        if (!readBuffer.hasRemaining()) {
          analyseBuffer(readBuffer);
          process();
        }
      }
    }

    public int getState() {
      return TRANSFERING;
    }
  }

  private static class StateClosed implements State {
    public void process() {}

    public int getState() {
      return DISCONNECTED;
    }
  }

  private int processSocket(ByteBuffer buffer, boolean doRead) {
    int processedBytes = 0;
    try {
      processedBytes = doRead ? socket.read(buffer) : socket.write(buffer);
    } catch (Exception e) {
      return -1;
    }
    return processedBytes;
  }

  public void process() {
    if(peerUpdater == null) {
      if(fake)
        initialize();
      peerUpdater = new PeerUpdater();
      peerUpdater.start();
    }
  }

  public int getState() {
    if (currentState != null)
      return currentState.getState();
    return 0;
  }

  public int getPercentDone() {
    int sum = 0;
    for (int i = 0; i < available.length; i++) {
      if (available[i])
        sum++;
    }

    sum = (sum * 1000) / available.length;
    return sum;
  }

  public boolean transfertAvailable() {
    return (!choked && interested);
  }

  private void analyseBuffer(ByteBuffer buffer) {
    buffer.position(0);
    int pieceNumber, pieceOffset, pieceLength;
    byte cmd = buffer.get();
    switch (cmd) {
      case BT_CHOKED :
        logger.log(componentID, evtProtocol, Logger.RECEIVED, ip + " is choking you");
        choked = true;
        cancelRequests();
        readMessage(readBuffer);
        break;
      case BT_UNCHOKED :
        logger.log(componentID, evtProtocol, Logger.RECEIVED, ip + " is unchoking you");
        choked = false;
        readMessage(readBuffer);
        break;
      case BT_INTERESTED :
        logger.log(componentID, evtProtocol, Logger.RECEIVED, ip + " is interested");
        interesting = true;
        readMessage(readBuffer);
        break;
      case BT_UNINTERESTED :
        logger.log(componentID, evtProtocol, Logger.RECEIVED, ip + " is not interested");
        interesting = false;
        readMessage(readBuffer);
        break;
      case BT_HAVE :
        pieceNumber = buffer.getInt();
        logger.log(componentID, evtProtocol, Logger.RECEIVED, ip + " has " + pieceNumber);
        have(pieceNumber);
        readMessage(readBuffer);
        break;
      case BT_BITFIELD :
        logger.log(componentID, evtProtocol, Logger.RECEIVED, ip + " has sent BitField");
        setBitField(buffer);
        checkInterested();
        checkSeed();
        readMessage(readBuffer);
        break;
      case BT_REQUEST :
        pieceNumber = buffer.getInt();
        pieceOffset = buffer.getInt();
        pieceLength = buffer.getInt();
        logger.log(
          componentID,
          evtProtocol,
          Logger.RECEIVED,
          ip + " has requested #" + pieceNumber + ":" + pieceOffset + "->" + (pieceOffset + pieceLength));
        if (manager.checkBlock(pieceNumber, pieceOffset, pieceLength)) {
          sendData(new Request(pieceNumber, pieceOffset, pieceLength));
        }
        else {
          logger.log(
            componentID,
            evtErrors,
            Logger.ERROR,
            ip
              + " has requested #"
              + pieceNumber
              + ":"
              + pieceOffset
              + "->"
              + pieceLength
              + " which is an invalid request.");
          closeAll();
          return;
        }
        readMessage(readBuffer);
        break;
      case BT_PIECE :
        pieceNumber = buffer.getInt();
        pieceOffset = buffer.getInt();
        pieceLength = buffer.limit() - buffer.position();
        logger.log(
          componentID,
          evtProtocol,
          Logger.RECEIVED,
          ip + " has sent #" + pieceNumber + ":" + pieceOffset + "->" + (pieceOffset + pieceLength));
        Request request = new Request(pieceNumber, pieceOffset, pieceLength);
        if (requested.contains(request) && manager.checkBlock(pieceNumber, pieceOffset, buffer)) {
          requested.remove(request);
          setSnubbed(false);
          reSetRequestsTime();
          manager.writeBlock(pieceNumber, pieceOffset, buffer);
          readMessage(ByteBufferPool.getInstance().getFreeBuffer());
        }
        else {
          logger.log(
            componentID,
            evtErrors,
            Logger.ERROR,
            ip
              + " has sent #"
              + pieceNumber
              + ":"
              + pieceOffset
              + "->"
              + (pieceOffset + pieceLength)
              + " but piece was discarded (either not requested or invalid)");
          readMessage(readBuffer);
        }
        break;
      case BT_CANCEL :
        pieceNumber = buffer.getInt();
        pieceOffset = buffer.getInt();
        pieceLength = buffer.getInt();
        logger.log(
          componentID,
          evtProtocol,
          Logger.RECEIVED,
          ip + " has canceled #" + pieceNumber + ":" + pieceOffset + "->" + (pieceOffset + pieceLength));
        removeRequestFromQueue(new Request(pieceNumber, pieceOffset, pieceLength));
        readMessage(readBuffer);
        break;
    }
  }

  private void have(int pieceNumber) {
    available[pieceNumber] = true;
    stats.haveNewPiece();
    manager.haveNewPiece();
    manager.havePiece(pieceNumber, this);
    if (!interested)
      checkInterested(pieceNumber);
    checkSeed();
  }

  /**
     * Checks if it's a seed or not.
     */
  private void checkSeed() {
    for (int i = 0; i < available.length; i++) {
      if (!available[i])
        return;
    }
    seed = true;
  }

  private void reSetRequestsTime() {
    for (int i = 0; i < requested.size(); i++) {
      Request request = null;
      try {
        request = (Request) requested.get(i);
      }
      catch (Exception e) {}
      if (request != null)
        request.reSetTime();
    }
  }

  public void request(int pieceNumber, int pieceOffset, int pieceLength) {
    if (getState() != TRANSFERING)
      return;
    logger.log(
      componentID,
      evtProtocol,
      Logger.SENT,
      ip + " is asked for #" + pieceNumber + ":" + pieceOffset + "->" + (pieceOffset + pieceLength));
    requested.add(new Request(pieceNumber, pieceOffset, pieceLength));
    ByteBuffer buffer = ByteBuffer.allocate(17);
    buffer.putInt(13);
    buffer.put(BT_REQUEST);
    buffer.putInt(pieceNumber);
    buffer.putInt(pieceOffset);
    buffer.putInt(pieceLength);
    buffer.position(0);
    buffer.limit(17);
    sendProtocol(buffer);
  }

  public void sendCancel(Request request) {
    if (getState() != TRANSFERING)
      return;
    logger.log(
      componentID,
      evtProtocol,
      Logger.SENT,
      ip
        + " is canceled for #"
        + request.getPieceNumber()
        + "::"
        + request.getOffset()
        + "->"
        + (request.getOffset() + request.getLength()));
    if (requested.contains(request)) {
      requested.remove(request);
      ByteBuffer buffer = ByteBuffer.allocate(17);
      buffer.putInt(13);
      buffer.put(BT_CANCEL);
      buffer.putInt(request.getPieceNumber());
      buffer.putInt(request.getOffset());
      buffer.putInt(request.getLength());
      buffer.position(0);
      buffer.limit(17);
      sendProtocol(buffer);
    }
  }

  public void sendHave(int pieceNumber) {
    if (getState() != TRANSFERING)
      return;
    logger.log(componentID, evtProtocol, Logger.SENT, ip + " is notified you have " + pieceNumber);
    ByteBuffer buffer = ByteBuffer.allocate(9);
    buffer.putInt(5);
    buffer.put(BT_HAVE);
    buffer.putInt(pieceNumber);
    buffer.position(0);
    buffer.limit(9);
    sendProtocol(buffer);
    checkInterested();
  }

  public void sendChoke() {
    if (getState() != TRANSFERING)
      return;
    logger.log(componentID, evtProtocol, Logger.SENT, ip + " is choked");
    choking = true;
    sendSimpleCommand(BT_CHOKED);
  }

  public void sendUnChoke() {
    if (getState() != TRANSFERING)
      return;
    logger.log(componentID, evtProtocol, Logger.SENT, ip + " is unchoked");
    choking = false;
    sendSimpleCommand(BT_UNCHOKED);
  }

  private void sendSimpleCommand(byte command) {
    ByteBuffer buffer = ByteBuffer.allocate(5);
    buffer.putInt(1);
    buffer.put(command);
    buffer.position(0);
    buffer.limit(5);
    sendProtocol(buffer);
  }

  private void setBitField(ByteBuffer buffer) {
    byte[] data = new byte[(manager.getPiecesNumber() + 7) / 8];
    buffer.get(data);
    for (int i = 0; i < available.length; i++) {
      int index = i / 8;
      int bit = 7 - (i % 8);
      byte bData = data[index];
      byte b = (byte) (bData >> bit);
      if ((b & 0x01) == 1) {
        available[i] = true;
      }
      else {
        available[i] = false;
      }
    }
  }

  /**
   * Global checkInterested method.
   * Scans the whole pieces to determine if it's interested or not
   */
  private void checkInterested() {
    boolean newInterested = false;
    boolean[] myStatus = manager.getPiecesStatus();
    for (int i = 0; i < myStatus.length; i++) {
      if (!myStatus[i] && available[i]) {
        newInterested = true;
      }
    }

    if (newInterested && !interested) {
      logger.log(componentID, evtProtocol, Logger.SENT, ip + " is interesting");
      sendSimpleCommand(BT_INTERESTED);
    }
    else if (!newInterested && interested) {
      logger.log(componentID, evtProtocol, Logger.SENT, ip + " is not interesting");
      sendSimpleCommand(BT_UNINTERESTED);
    }
    interested = newInterested;
  }

  /**
   * Checks interested given a new piece received
   * @param pieceNumber the piece number that has been received
   */
  private void checkInterested(int pieceNumber) {
    boolean[] myStatus = manager.getPiecesStatus();
    boolean newInterested = !myStatus[pieceNumber];
    if (newInterested && !interested) {
      logger.log(componentID, evtProtocol, Logger.SENT, ip + " is interesting");
      sendSimpleCommand(BT_INTERESTED);
    }
    else if (!newInterested && interested) {
      logger.log(componentID, evtProtocol, Logger.SENT, ip + " is not interesting");
      sendSimpleCommand(BT_UNINTERESTED);
    }
    interested = newInterested;
  }

  /**
   * Private method to send the bitfield.
   * The bitfield will only be sent if there is at least one piece available.
   *
   */
  private void sendBitField() {
    ByteBuffer buffer = ByteBuffer.allocate(5 + (manager.getPiecesNumber() + 7) / 8);
    buffer.putInt(buffer.capacity() - 4);
    buffer.put(BT_BITFIELD);
    boolean atLeastOne = false;
    boolean[] myStatus = manager.getPiecesStatus();
    int bToSend = 0;
    int i = 0;
    for (; i < myStatus.length; i++) {
      if ((i % 8) == 0)
        bToSend = 0;
      bToSend = bToSend << 1;
      if (myStatus[i]) {
        bToSend += 1;
        atLeastOne = true;
      }
      if ((i % 8) == 7)
        buffer.put((byte) bToSend);
    }
    if ((i % 8) != 0) {
      bToSend = bToSend << (8 - (i % 8));
      buffer.put((byte) bToSend);
    }

    buffer.position(0);
    if (atLeastOne) {
      buffer.limit(buffer.capacity());
      sendProtocol(buffer);
      logger.log(componentID, evtProtocol, Logger.SENT, ip + " is sent your bitfield");
    }
  }

  private void removeRequestFromQueue(Request request) {
    for (int i = 0; i < dataQueue.size(); i++) {
      DataQueueItem item = (DataQueueItem) dataQueue.get(i);
      if (item.getRequest().equals(request)) {
        if (item.isLoaded()) {
          ByteBufferPool.getInstance().freeBuffer(item.getBuffer());
        }
        if (item.isLoading()) {
          manager.freeRequest(item);
        }
        dataQueue.remove(item);
        i--;
      }
    }
  }

  public void cancelRequests() {
    if (requested == null)
      return;
    synchronized (requested) {
      for (int i = requested.size() - 1; i >= 0; i--) {
        Request request = (Request) requested.remove(i);
        manager.requestCanceled(request);
      }
    }
  }

  public int getNbRequests() {
    return requested.size();
  }

  public List getExpiredRequests() {
    Vector result = new Vector();
    for (int i = 0; i < requested.size(); i++) {
      try {
        Request request = (Request) requested.get(i);
        if (request.isExpired()) {
          result.add(request);
        }
      }
      catch (ArrayIndexOutOfBoundsException e) {
        //Keep going, most probably, piece removed...
        //Hopefully we'll find it later :p
      }
    }
    return result;
  }

  protected void write() { //If we are already sending something, we simply continue
    if (writeBuffer != null) {
      try {
        int realLimit = writeBuffer.limit();
        int limit = realLimit;
        int uploadAllowed = 0;
        if (writeData && SpeedLimiter.getLimiter().isLimited(this)) {
          if ((loopFactor % 5) == 0) {
            allowed = SpeedLimiter.getLimiter().getLimitPer100ms(this);
            used = 0;
          }
          uploadAllowed = allowed - used;
          limit = writeBuffer.position() + uploadAllowed;
          if (limit > realLimit)
            limit = realLimit;
        }
        writeBuffer.limit(limit);
        int written = processSocket(writeBuffer, false);
        // End of Stream Reached
        if (written >= 0) {
          writeBuffer.limit(realLimit);

          if (writeData) {
            stats.sent(written);
            manager.sent(written);
            if (SpeedLimiter.getLimiter().isLimited(this)) {
              used += written;
              if ((loopFactor % 5) == 4) {
                if (used >= allowed) // (100 * allowed) / 100
                  maxUpload = max(110 * allowed, 20);
                if (used < (95 * allowed) / 100)
                  maxUpload = max((100 * written) / 100, 20);
              }
            }
          }
        } else {
          closeAll();
        }
      }
      catch (Exception e) {
        //e.printStackTrace();
        closeAll();
      }
      //If we have finished sending this buffer
      if (!writeBuffer.hasRemaining()) {
        //If we were sending data, we must free the writeBuffer
        if (writeData) {
          ByteBufferPool.getInstance().freeBuffer(writeBuffer);
          SpeedLimiter.getLimiter().removeUploader(this);
        }
        //We set it to null
        writeBuffer = null;
      } //So if we haven't written the whole buffer, we simply return...
    }

    if (writeBuffer == null) {
      //So the ByteBuffer is null ... let's find out if there's any data in the protocol queue
      if (protocolQueue.size() != 0) {
        //Assign the current buffer ...
        keepAlive = 0;
        writeBuffer = (ByteBuffer) protocolQueue.remove(0);
        writeBuffer.position(0);
        writeData = false;
        //and loop
        write();
      }
      if (dataQueue.size() != 0) {
        DataQueueItem item = (DataQueueItem) dataQueue.get(0);
        if (!choking) {
          if (!item.isLoading() && !choking) {
            manager.enqueueReadRequest(item);
            item.setLoading(true);
          }
          if (item.isLoaded()) {
            dataQueue.remove(0);
            if (dataQueue.size() != 0) {
              DataQueueItem itemNext = (DataQueueItem) dataQueue.get(0);
              if (!itemNext.isLoading()) {
                manager.enqueueReadRequest(itemNext);
                itemNext.setLoading(true);
              }
            }
            Request request = item.getRequest();
            logger.log(
              componentID,
              evtProtocol,
              Logger.SENT,
              ip
                + " is being sent #"
                + request.getPieceNumber()
                + ":"
                + request.getOffset()
                + "->"
                + (request.getOffset() + request.getLength()));
            // Assign the current buffer ...
            keepAlive = 0;
            writeBuffer = item.getBuffer();
            writeBuffer.position(0);
            writeData = true;
            SpeedLimiter.getLimiter().addUploader(this);
            // and loop
            write();
          }
        }
        else {
          //We are choking the peer so ...
          if (!item.isLoading()) {
            dataQueue.remove(item);
          }
          if (item.isLoaded()) {
            dataQueue.remove(item);
            ByteBufferPool.getInstance().freeBuffer(item.getBuffer());
          }
          return;
        }
      }
      if ((protocolQueue.size() == 0) && (dataQueue.size() == 0)) {
        keepAlive++;
        if (keepAlive == 100 * 120) {
          keepAlive = 0;
          sendKeepAlive();
        }
      }
    }
  }

  private int max(int a, int b) {
    return a > b ? a : b;
  }

  private void sendKeepAlive() {
    ByteBuffer buffer = ByteBuffer.allocate(4);
    buffer.putInt(0);
    buffer.limit(4);
    buffer.position(0);
    protocolQueue.add(buffer);
  }

  public int getMaxUpload() {
    return maxUpload;
  }

  public DataQueueItem getNextRequest() {
    if (dataQueue.size() != 0)
      return (DataQueueItem) dataQueue.get(0);
    return null;
  }

  //The SocketChannel associated with this peer
  private SocketChannel socket;

  //The reference to the current ByteBuffer used for reading on the socket.
  private ByteBuffer readBuffer;
  //The Buffer for reading the length of the messages
  private ByteBuffer lengthBuffer;
  //A flag to indicate if we're reading the length or the message
  private boolean readingLength;
  //The reference tp the current ByteBuffer used to write on the socket
  private ByteBuffer writeBuffer;
  //A flag to indicate if we're sending protocol messages or data
  private boolean writeData;
  private int allowed;
  private int used;
  private int loopFactor;

  private PeerUpdater peerUpdater;

  private boolean fake = false;

  //The keepAlive counter
  private int keepAlive;

  //The Queue for protocol messages
  private List protocolQueue;

  //The Queue for data messages
  private List dataQueue;
  private boolean incoming;
  private boolean closing;
  private State currentState;

  //The maxUpload ...
  int maxUpload = 1024 * 1024;

  //The client
  String client = "";

  //The Logger
  private Logger logger;
  public final static int componentID = 1;
  public final static int evtProtocol = 0;
  // Protocol Info
  public final static int evtLifeCycle = 1;
  // PeerConnection Life Cycle
  public final static int evtErrors = 2;
  // PeerConnection Life Cycle
  private final static String PROTOCOL = "BitTorrent protocol";
  private final static byte BT_CHOKED = 0;
  private final static byte BT_UNCHOKED = 1;
  private final static byte BT_INTERESTED = 2;
  private final static byte BT_UNINTERESTED = 3;
  private final static byte BT_HAVE = 4;
  private final static byte BT_BITFIELD = 5;
  private final static byte BT_REQUEST = 6;
  private final static byte BT_PIECE = 7;
  private final static byte BT_CANCEL = 8;

  public final static int CONNECTING = 10;
  public final static int HANDSHAKING = 20;
  public final static int TRANSFERING = 30;
  public final static int DISCONNECTED = 40;
  /**
   * @return
   */
  public boolean isIncoming() {
    return incoming;
  }

  public int getDownloadPriority() {
    return manager.getDownloadPriority();
  }

  /**
   * @return
   */
  public String getClient() {
    return client;
  }

  public boolean isOptimisticUnchoke() {
    return manager.isOptimisticUnchoke(this);
  }

}
