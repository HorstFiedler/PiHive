package at.hfff.pi.ws;

import static at.hfff.pi.PiHive.LOG;   // main class logger used
import at.hfff.pi.PiHive;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.logging.Level;
import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import javax.websocket.Session;

/**
 * Avoiding Annotations to configure websocket endpoint TODO: adapt to allow
 * async. messages to client (see tomcat drawboard example!)
 *
 * @author horst
 */
public class PiEndpoint extends Endpoint {

  @Override
  public void onOpen(Session session, EndpointConfig endpointConfig) {
    // allow simple output buffering
    OutputFeeder of = new OutputFeeder(session);
    
    // no segmentation yet, SVG's shall be smaler
    session.setMaxTextMessageBufferSize(25000);
    session.addMessageHandler(new TextHandler(of));
    session.addMessageHandler(new BinaryHandler(of));

    StringBuilder sb = new StringBuilder();
    // pi is instantiated (and configured) by servletcontextlistener
    PiHive pi = PiHive.getInstance();
    if (pi.isAlive()) {
      pi.addClient(session.getId(), of);   
      sb.append("Pi session ").append(session.getId()).append(" ready");
    } else {
      sb.append("Pi mainloop not active, restart servlet!");
    }
    LOG.log(Level.INFO, "{0}", sb);
    of.send(sb.toString());
  }

  @Override
  public void onClose(Session session, CloseReason closeReason) {
    PiHive.getInstance().removeClient(session.getId());
    LOG.log(Level.FINE, "Session {0} closed due to {1}", new Object[]{session.getId(), closeReason.getReasonPhrase()});
  }

  /**
   * Occurs when client logged out and still message sent to client
   * @param session
   * @param thr 
   */
  @Override
  public void onError(Session session, Throwable thr) {
    PiHive.getInstance().removeClient(session.getId());
    LOG.log(Level.FINE, "Session " + session.getId() + " error: ", thr);
  }

  /**
   * Input handler
   */
  private static class TextHandler implements MessageHandler.Whole<String> {

    private final OutputFeeder of;

    private TextHandler(OutputFeeder of) {
      this.of = of;
    }

    @Override
    public void onMessage(String msg) {
      LOG.log(Level.FINE, msg);
      PiHive pi = PiHive.getInstance();
      String[] cmdA = msg.split(" ", 3);
      StringBuilder sb = new StringBuilder();
      try {
        switch (cmdA[0]) {
          case "syscmd": // various 2way (set/get) function calls
            sb.append(pi.sysCommand(cmdA[1], cmdA.length > 2 ? cmdA[2] : ""));
            break;
          case "setpin": // set output pin (0 == true/low, 1 == false/high)     
            sb.append(pi.setPinValue(cmdA[1], Integer.parseInt(cmdA[2])));
            break;
          case "getpin": // get in- or output pin value by name
            sb.append(pi.getPinValue(cmdA[1]));
            break;
          case "setvar": // set internal variable (integers from PwrCtl to broadcast #timeouts, ...)
            pi.setValue(cmdA[1], cmdA[2]);
            break;
          case "getvar": // get internal variable
            sb.append(pi.getValue(cmdA[1]));
            break;
          case "stop": // force mainloop exit (experimental)
            pi.terminate();
            sb.append("stop is pure experimental, use servlet stop instead");
            break;
          default:
            // check for arguments (Pattern matching), e.g. get <channelname>, put <channelname> <value>
            sb.append(cmdA[0]).append(": invalid command");
        }
      } catch (IllegalArgumentException ex) {
        LOG.log(Level.SEVERE, msg, ex);
        sb.append(msg).append(" failed: ").append(ex.getMessage());
        if (ex.getMessage().contains("rejected")) // tell client current state
        {
          try {
            sb.append('\n').append(pi.getPinValue(cmdA[1]));
          } catch (IllegalArgumentException e) {
          }
        }
      } catch (Exception e) {
        LOG.log(Level.SEVERE, msg, e);
      }
      if (sb.length() > 0) {// 0 will cause illegal java.lang.IllegalArgumentException at java.nio.Buffer.limit(Buffer.java:275)
        of.send(sb.toString());
        LOG.log(Level.FINE, "{0}", sb);
      }
    }
  }

  private static class BinaryHandler implements MessageHandler.Whole<ByteBuffer> {

    private final OutputFeeder of;

    private BinaryHandler(OutputFeeder of) {
      this.of = of;
    }

    @Override
    public void onMessage(ByteBuffer message) {
      LOG.log(Level.WARNING, "Binary message not yet implemented");

    }
  }
  
  /**
   * public access due to access from Pi.class
   */
  public static class OutputFeeder implements SendHandler {
    private final Session session;
    private boolean isSending = false;
    private final LinkedList<String> messagesToSend = new LinkedList<>();
    
    private OutputFeeder (Session session) {
      this.session = session;
      session.getAsyncRemote().setSendTimeout(5000);
    }
    
    public void send(String msg) {
       synchronized (messagesToSend) {
         if (isSending) {
           // put message to queue
           //TODO: create protocoll allowing to concatenate messages, e.g. using | or formfeed separator 
           messagesToSend.add(msg);         
         } else {
           isSending = true;
           session.getAsyncRemote().sendText(msg, this);
         }
       }
    }
    
    @Override
    public void onResult(SendResult sr) {
      if (sr.isOK()) {
        // pick next from concurrentqueue
        if (messagesToSend.isEmpty()) {
          isSending = false;
        } else {
          String msg = messagesToSend.remove();
          isSending = true;
          session.getAsyncRemote().sendText(msg, this);
        }      
      } else {  
        // Message could not be sent. In this case, we don't
        // set isSendingMessage to false because we must assume the connection
        // broke (and onClose will be called), so we don't try to send
        // other messages.
        LOG.log(Level.WARNING, "Send failed", sr.getException());
        // As a precaution, we close the session (e.g. if a send timeout occured).
        // TODO: session.close() blocks, while this handler shouldn't block.
        // Ideally, there should be some abort() method that cancels the
        // connection immediately...
        try {
          session.close();
        } catch (IOException ex) {
          // Ignore
        }
      }
    }
  };

}
