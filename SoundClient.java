import java.io.*;
import java.nio.file.*;
import java.net.*;
import java.util.regex.*;
import javax.sound.sampled.*;
import javax.sound.sampled.LineEvent.Type;

import static util.SoundUtil.*;

public class SoundClient { 

  private static String loggingName = "SoundClient";

  //private static String audioFilename = "Roland-JX-8P-Bell-C5.wav";
  //private static String audioFilename = "Roland-GR-1-Trumpet-C5.wav";

  private final String audioFilename;

  private String tcpHost;
  private int tcpPort;

  private static String defaultHost = "localhost";
  private static int defaultPort = 789;

  private int id; // todo: make same as thread.getId();
  private static int defaultId = 0;

  //private String role; // todo: use enum/check for valid role reply from server.
  //private String role; // todo: use enum/check for valid role reply from server.

  private BufferedReader bufferedReader;
  private PrintWriter printWriter;
  private Socket sock;
  private InputStreamReader isr;

  private boolean udpReceiverIsUp;

  private DatagramSocket udpReceiverSocket;
  private int udpReceiverPort;

  private DatagramSocket udpSocket;
  private InetAddress udpHost;
  private int udpPort;

  private MulticastSocket mcSocket;
  private InetAddress mcGroup;
  private static String mcAddress = "224.111.111.111";
  private static int mcPort = 10000;

  private static final int udpMaxPayload = 512; // Seems best practice not to exceeed this.

  private static int resetClient = -1; // This needs to be the same on the client and server thread for failover purposes. 
  
  private enum Role {
    NOT_SET,
    SENDER,
    RECEIVER
  }

  private Role role;

  private enum Request {
    ID,
    ROLE,
    UDP_PORT,
    ACK_LENGTH,
    READY_TO_SEND
  }

  private enum Replies { 
    ACK_LENGTH
  }

  byte[] soundBytesToSend; // if sender
  byte[] soundBytes; // if receiver

  private int arrayLength; // todo: change variable name?

  public SoundClient(String audioFilename) {
    this(defaultHost, defaultPort, audioFilename);
  }

  public SoundClient(String host, int port, String audioFilename) {
    tcpHost = host;
    tcpPort = port;
    id = defaultId;
    role = Role.NOT_SET;
    udpReceiverIsUp = false;
    this.audioFilename = audioFilename;
  }

  public static void main(String[] args) { 

    String filename = null;

    if (args.length == 1) { 
      filename = args[0];
    } else { 
      System.out.println("Usage: java " + loggingName + " <wav_filename>");
      System.exit(0);
    }

    SoundClient soundClient = new SoundClient(filename);  
    soundClient.launch();

  }


  private void launch() { 

    connectTcp();
    setUpTcpIo();
    requestAndSetId();
    requestAndSetRole();
    requestAndSetUdpPort();
    setUpUdpSending();

    // main loop:

    while(true) {  

      if (getRole() == Role.SENDER) { 
        readSoundFileIntoByteArray(audioFilename);
        tcpSendArrayLength();
        int audioSendCount = 0;
        
        // sender loop:

        while(true) { 
          System.out.println();
          log("Audio send count: " + audioSendCount++);
          String reply = tcpWaitForMessage("READY_TO_RECEIVE");
          if (reply == null) {
            error("Lost connection with receiver on server thread.");
            //break;
            System.exit(0);
          }
          tcpSend("READY_TO_SEND");
          try {
            Thread.sleep(1000); // todo: remove after testing?
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
          udpSendSoundBytesToServerThread();
        }
      }

      else if (getRole() == Role.RECEIVER) { 
        udpSetUpReceiverSocket();

        // receiver loop:

        while(true) {

          System.out.println();
          tcpSend("READY_FOR_ARRAY_LENGTH"); // todo: what if this is sent before ServerThread does tcpWaitForMessage("READY_FOR_ARRAY_LENGTH")? Not heard!!
          String length = tcpListen(); // todo: what if connection is broken and length is null? Add exception handling to tcpListen();
          log("Received array length: " + length);
          setArrayLength(Integer.parseInt(length)); 

          if (getArrayLength() == resetClient) { 
            // This means there is a failover situation.
            // Become a sender instead of a receiver.
            setRole(Role.SENDER);
            log("Role changing from RECEIVER to SENDER");
            break;
          }

          soundBytes = new byte[getArrayLength()];
          tcpWaitForMessage("READY_FOR_UDP_PORT");
          tcpSend(new Integer(getUdpReceiverPort()).toString());
          tcpSend("READY_TO_RECEIVE"); 
          udpReceiveAudioFromSender();
          playAudio(soundBytes); // rename

        } // end of receiver loop
      } // end of receiver block
    } // end of main while loop 

  }

  private int getUdpReceiverPort() {
    return udpReceiverSocket.getLocalPort();
  }

  private void udpSetTimeout(int ms) {
    try {
      udpSocket.setSoTimeout(ms);
    } catch (SocketException e) {
      e.printStackTrace();
    }
  }

  
  private void udpReceiveAudioFromSender() {
    DatagramPacket packet;
    byte[] packetBytes = new byte[udpMaxPayload];

    int byteI = 0;

    try {
      udpReceiverSocket.setSoTimeout(2000);
    } catch (SocketException e) {
      e.printStackTrace();
    }

    //log("Receiving byte " + byteI);

    // get packets with constant payload size (udpMaxPayload)
    int arrLen = getArrayLength();
    while (byteI < arrLen - udpMaxPayload) {
        //log("Receiving byte " + byteI);
        packet = new DatagramPacket(packetBytes, packetBytes.length);

        try {
          //udpSocket.receive(packet);
          udpReceiverSocket.receive(packet);
        } catch (SocketTimeoutException e) {
          log("**** UDP TIMEOUT ****");
          break; // This is the normal course of events.
        } catch (IOException e) {
          e.printStackTrace();
        }

        try { 
          System.arraycopy(packetBytes, 0, soundBytes, byteI, packetBytes.length);
        } catch (ArrayIndexOutOfBoundsException e) { 
          e.printStackTrace();
          log("packetBytes.length: " + packetBytes.length);
          log("soundBytes.length: " + soundBytes.length);
          log("byteI: " + byteI);
          log("arrLen: " + arrLen);
        }

        byteI += udpMaxPayload;
    }

    //udpSetTimeout(5000); // todo: remove because for testing only, ie so we have time to start client in terminal.

    /*
    // get final packet, size being what ever is left after getting contant length packets.
    if (byteI < arrLen) {
      int finLen = arrLen - byteI;
      byte[] finBytes = new byte[finLen];
      packet = new DatagramPacket(finBytes, finLen);

      try {
        udpReceiverSocket.receive(packet);
      } catch (SocketTimeoutException e) {
        //break; // This is the normal course of events.
      } catch (IOException e) {
        e.printStackTrace();
      }

      System.arraycopy(finBytes, 0, soundBytes, byteI, finLen);
      byteI += finLen;
    }
    */


    log("Received final byte: " + byteI);

    udpSetTimeout(100);

  }

  private String tcpListen() {
    String msg = null;
    try {
      msg = bufferedReader.readLine();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return msg;
  }

  private String tcpExpectAndSend(String expected, String sendThis) {
    String request = null;
    log("Waiting for message from SoundServerThread: " + expected);
    request = tcpListen();
    log("Message received.");
    if (request.startsWith(expected)) {
      log("Message received was as expected.");
      printWriter.println(sendThis);
      log("Sent this message: " + sendThis);
    } else {
      log("SoundServerThread sent this instead of '" + expected + "': " + request);
    }
    return request;
  }

  private void tcpExpectAndSetArrayLength() {
    String request = tcpExpectAndSend("ACK_LENGTH", "ACK_LENGTH"); // todo: rewrite, this is confusing.
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher(request);
    m.find();
    setArrayLength(Integer.parseInt(m.group(0)));
    log("Array length set to " + getArrayLength());
  }
  
  private void setArrayLength(int len) {
    arrayLength = len;
  }

  private int getArrayLength() {
    return arrayLength;
  }



  private void tcpSend(String message) {
    log("Sending TCP message: " + message);
    printWriter.println(message);
  }


  private void udpSetUpReceiverSocket() {
    if (!udpReceiverIsUp) {
      try {
        udpReceiverSocket = new DatagramSocket();
        udpReceiverIsUp = true;
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }


  private void playAudio(byte[] bytes) {

    log("Playing audio byte array of length " + bytes.length);
    try {
      //ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
      ByteArrayInputStream bais = new ByteArrayInputStream(bytes, 0, bytes.length);
      log("available: " + bais.available());

      AudioInputStream stream; // input stream with format and length
      AudioFormat format;
      DataLine.Info info;
      Clip clip;

      stream = AudioSystem.getAudioInputStream(bais);
      format = stream.getFormat();
      info = new DataLine.Info(Clip.class, format);
      clip = (Clip) AudioSystem.getLine(info);
      clip.open(stream);
      clip.start();
      do {
        Thread.sleep(1);
      } while (clip.isActive());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void tcpSendArrayLength() { 
    String request = Replies.ACK_LENGTH.toString() + " " + soundBytesToSend.length;
    String reply = tcpRequest(request);

    if (reply != null && reply.startsWith(Replies.ACK_LENGTH.toString()))
      log("Server thread says it's ready to receive audio of requested length.");
    else { 
      log("Unexpected reply when sending array length.");
      // todo: handle problem (need to do this everwhere if there's time)
    }
  }

  private void readSoundFileIntoByteArray(String filename) { 
    Path path = Paths.get(filename);

    try { 
      log("Reading file " + filename + " into byte array.");
      soundBytesToSend = Files.readAllBytes(path);
    } catch (IOException e) { 
      e.printStackTrace();
    }
  }

  private void udpSendSoundBytesToServerThread() { 

    DatagramPacket packet;

    int i = 0;
    
    log("Sending sound to server thread.");
    while (i < soundBytesToSend.length - udpMaxPayload) { 
      //log("i: " + i);
      packet = new DatagramPacket(soundBytesToSend, i, udpMaxPayload, udpHost, udpPort);
      try { 
        udpSocket.send(packet);
      } catch (IOException e) { 
        e.printStackTrace();
      }
      i += udpMaxPayload;
    }
  }

  private void setUpUdpSending() { 
    try { 
      udpSocket = new DatagramSocket();
    } catch (IOException e) { 
      e.printStackTrace();
    }
    try { 
      udpHost = InetAddress.getByName(defaultHost); // todo: get it working over the network.
    } catch (UnknownHostException e) { 
      e.printStackTrace();
    }
  }


  private void connectTcp() { 

    // Set up socket

    log("Setting up TCP connection with server.");
    try { 
      sock = new Socket(tcpHost, tcpPort);
    } catch (ConnectException e) { // Connection refused
      e.printStackTrace();
    } catch (UnknownHostException e) { // couldn't resolve name. 
      e.printStackTrace();
    } catch (IOException e) { // some other problem setting up socket 
      e.printStackTrace();
    }
  }

  private void setUpTcpIo() { 

    // Set up I/O over socket 

    log("Setting up TCP IO streams with server.");
    try { 
      isr = new InputStreamReader(sock.getInputStream());
      bufferedReader = new BufferedReader(isr);
      //printWriter = new PrintWriter(sock.getOutputStream(), true); // true autoFlushes output buffer
      printWriter = new PrintWriter(sock.getOutputStream(), true);
    } catch (IOException e) { 
      e.printStackTrace();
    }
  }

  private int getId() { 
    return id;
  }

  private Role getRole() { 
    return role;
  }

  private void setRole(Role role) { 
    this.role = role;
  }

  private int getUdpPort() { 
    return udpPort;
  }

  private void requestAndSetId() { 
    String request = "ID";
    String reply = tcpRequest(request);
    if (reply != null) { 
      id = Integer.parseInt(reply); 
      log(request + " received: " + getId());
    } else { 
      log("Got null reply from server when requesting " + request);
    }
  }

  private void requestAndSetUdpPort() { 
    String request = "UDP_PORT";
    String reply = tcpRequest(request);
    if (reply != null) { 
      udpPort = Integer.parseInt(reply); 
      log(request + " received: " + getUdpPort());
    } else { 
      log("Got null reply from server when requesting " + request);
    }
  }

  private void requestAndSetRole() {  // todo: DRY
    String request = "ROLE";
    String reply = tcpRequest(request);

    if (reply == null) 
      log("Got null reply from server when requesting " + request);
    else {
      if (reply.contains("RECEIVER")) { 
        role = Role.RECEIVER;
        log("Role set to " + getRole()); 
      }
      else if (reply.contains("SENDER")) {
        role = Role.SENDER;
        log("Role set to " + getRole()); 
      }
      else {
        log("Unexpected reply from server when requesting " + request + ": " + reply);
      }
    }

    // todo: add exceptions (everywhere) for bad/no replies.
  }

  private String tcpRequest(String request) { 
    String reply = null;
    if (printWriter != null) { 
      log("Requesting " + request + " from server.");
      printWriter.println(request);   
      try { 
        reply = bufferedReader.readLine();
      } catch (IOException e) { 
        e.printStackTrace(); 
      }
    } else { 
      log("Can't request " + request + " - no IO stream set up with server.");
    }
    return reply;
  }

  private String tcpSendAndWaitForReply(String message) { 
    String reply = null;
    if (printWriter != null) { 
      log("Sent TCP message: " + message);
      printWriter.println(message);   
      try { 
        reply = bufferedReader.readLine();
        log("Received TCP reply from server: " + reply);
      } catch (IOException e) { 
        e.printStackTrace(); 
      }
    } else { 
      log("Can't send message - no IO stream set up with server.");
    }
    return reply;
  }

  private String tcpWaitForMessage(String message) { 
    log("Waiting for TCP message: " + message);
    try { 
      message = bufferedReader.readLine();
      log("Received TCP message: " + message);
    } catch (IOException e) { 
      e.printStackTrace(); 
    }
    return message;
  }

  private void log(String msg) { 
    logger(loggingName + "-" + getId(), msg);
  }

  // only use this when we shouldn't have gotten somewhere:
  private void error(String msg) { 
    logger(loggingName + "-" + getId() + ": ERROR",  msg);
  }

}
