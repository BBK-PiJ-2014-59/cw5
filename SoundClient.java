import java.io.*;
import java.nio.file.*;
import java.net.*;
import java.util.regex.*;
import javax.sound.sampled.*;

import static util.SoundUtil.*;

public class SoundClient { 

  private static String loggingName = "SoundClient";

  private static String audioFilename = "Roland-JX-8P-Bell-C5.wav";
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
  byte[] soundBytesToPlay; // if receiver

  byte[] soundBytes;
  private int arrayLength; // todo: change variable name?

  public SoundClient() {
    this(defaultHost, defaultPort);
  }

  public SoundClient(String host, int port) {
    tcpHost = host;
    tcpPort = port;
    id = defaultId;
    role = Role.NOT_SET;
    udpReceiverIsUp = false;
  }

  public static void main(String[] args) { 

    SoundClient soundClient = new SoundClient();  

    soundClient.connectTcp();
    soundClient.setUpTcpIo();
    soundClient.requestAndSetId();
    soundClient.requestAndSetRole();
    soundClient.requestAndSetUdpPort();
    soundClient.setUpUdpSending();


    if (soundClient.getRole() == Role.SENDER) { 
      soundClient.readSoundFileIntoByteArray(audioFilename);
      soundClient.tcpSendArrayLength();
      int audioSendCount = 0;
      while(true) { 
        soundClient.log("Audio send count: " + audioSendCount++);
        String reply = soundClient.tcpWaitForMessage("READY_TO_RECEIVE");
        if (reply == null) {
          soundClient.log("Lost connection with receiver on server thread.");
          break;
        }
        soundClient.tcpSend("READY_TO_SEND");
        try {
          Thread.sleep(1000); // todo: remove after testing?
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        soundClient.udpSendSoundBytesToServerThread();
      }
    }

    else if (soundClient.getRole() == Role.RECEIVER) { 
      soundClient.udpSetUpReceiverSocket();
      while(true) {

        System.out.println();
        soundClient.tcpSend("READY_FOR_ARRAY_LENGTH"); // todo: what if this is sent before ServerThread does tcpWaitForMessage("READY_FOR_ARRAY_LENGTH")? Not heard!!
        String length = soundClient.tcpListen(); // todo: what if connection is broken and length is null? Add exception handling to tcpListen();
        soundClient.log("Received array length: " + length);
        soundClient.setArrayLength(Integer.parseInt(length)); 
        if (soundClient.soundBytes == null)   
          soundClient.soundBytes = new byte[soundClient.getArrayLength()];
        soundClient.tcpWaitForMessage("READY_FOR_UDP_PORT");
        soundClient.tcpSend(new Integer(soundClient.getUdpReceiverPort()).toString());
        soundClient.tcpSend("READY_TO_RECEIVE"); // 
        soundClient.udpReceiveAudioFromSender();
        soundClient.playAudio(soundClient.soundBytes); // should this be non-threaded?
      }
    }
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

    int i = 0;

    udpSetTimeout(100);

    log("Receiving byte " + i);

    // get packets with constant payload size (udpMaxPayload)
    int arrLen = getArrayLength();
    while (i < arrLen - udpMaxPayload) {
        packet = new DatagramPacket(packetBytes, packetBytes.length);

        try {
          udpSocket.receive(packet);
        } catch (SocketTimeoutException e) {
          break; // This is the normal course of events.
        } catch (IOException e) {
          e.printStackTrace();
        }

        System.arraycopy(packetBytes, 0, soundBytes, i, packetBytes.length);
        i += udpMaxPayload;
    }

    //udpSetTimeout(5000); // todo: remove because for testing only, ie so we have time to start client in terminal.

    // get final packet, size being what ever is left after getting contant length packets.
    if (i < arrLen) {
      int finLen = arrLen - i;
      byte[] finBytes = new byte[finLen];
      packet = new DatagramPacket(finBytes, finLen);

      try {
        udpSocket.receive(packet);
      } catch (SocketTimeoutException e) {
        //break; // This is the normal course of events.
      } catch (IOException e) {
        e.printStackTrace();
      }

      System.arraycopy(finBytes, 0, soundBytes, i, finLen);
      i += finLen;
    }


    log("Received final byte: " + i);

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

  private void oldSendLoop() { 
      readSoundFileIntoByteArray(audioFilename);
      playAudio(getSoundBytesToSend()); // test play
      tcpSendArrayLength();
      udpSendSoundBytesToServerThread();
  }

  private byte[] getSoundBytesToSend() { 
    return soundBytesToSend;
  }

  private byte[] getSoundBytesToPlay() { 
    return soundBytesToPlay;
  }

  private void playAudio(byte[] bytes) {

    int sampleRate = 44100;
    int sampleSize = 16;
    int channels = 2;
    boolean signed = true;
    boolean bigEndian = false;

    AudioFormat format = new AudioFormat(sampleRate, sampleSize, channels, signed, bigEndian);

    try {
      DataLine.Info dataLineInfo = new DataLine.Info(SourceDataLine.class, format);
      SourceDataLine sourceDataLine = (SourceDataLine) AudioSystem.getLine(dataLineInfo);
      sourceDataLine.open(format);
      sourceDataLine.start();
      sourceDataLine.write(bytes, 0, bytes.length);
      sourceDataLine.drain();
      sourceDataLine.close();
    } catch (Exception e) { // todo: make exceptions more specific
      e.printStackTrace();
    }
  }

  //private void mcReceiveAudioBroadcast() { 
  private byte[] mcReceiveAudioBroadcast() { 
    log("Listening for multicast audio broadcast.");
    DatagramPacket packet;  
    ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
    //byte[] result = new byte[1000000];
    byte[] packetBytes = new byte[udpMaxPayload];

    mcSetTimeout(1000);

    int i = 0;
    while(true) { 
      packet = new DatagramPacket(packetBytes, packetBytes.length);
      try { 
        mcSocket.receive(packet);
      } catch (SocketTimeoutException e) {
        log("TIMEOUT"); 
        break; // This is the normal course of events.
      } catch (IOException e) {
        e.printStackTrace();
      }
      byteStream.write(packetBytes, 0, packetBytes.length);
      //System.arraycopy(packetBytes, 0, result, i, packetBytes.length); 
      i += udpMaxPayload;
    }
    byte[] result = byteStream.toByteArray();

    log("Bytes to play: " + result.length); 
    return result;
  }

  private void mcReceiveString(int len) { 
    byte[] buf = new byte[len];
    DatagramPacket mcPacket = new DatagramPacket(buf, buf.length);
    try { 
      log("Listening for multicast");
      mcSocket.receive(mcPacket);
      log("Received: " + new String(buf));
    } catch (IOException e) { 
      e.printStackTrace();
    }
  }

  private void mcSetTimeout(int ms) {
    try {
      mcSocket.setSoTimeout(ms);
    } catch (SocketException e) {
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

  private void udpSendString(String msg) { 
    log("Sending string via UDP: " + msg);
    byte[] bytes = msg.getBytes();
    DatagramPacket packet = new DatagramPacket(bytes, bytes.length, udpHost, udpPort);
    if (packet == null)
      log("packet null"); 
    try { 
      udpSocket.send(packet);
    } catch (IOException e) { 
      e.printStackTrace();
    }
  }



  private void mcSetUpReceiver() {
    log("Setting up multicast receiver.");
    try {
      mcSocket = new MulticastSocket(mcPort);
      mcGroup = InetAddress.getByName(mcAddress);
      mcSocket.joinGroup(mcGroup);
    } catch (UnknownHostException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
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

}
