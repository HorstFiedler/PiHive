package at.hfff.pi;

import static at.hfff.pi.PiHive.LOG;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Poster just writes, if target contains &lt;date&gt; that string is replaced by current date
 * empty pattern check (aka for posting disabled) shall be done by caller
 * @author horst
 */
public class Poster implements Serializable {
  private static final DateFormat DDF = new SimpleDateFormat("yyMMdd-HHmm");
 
  // to be shows at client side when there was no serialized Publisher
  String urlpattern = ""; // no send
  int delay = 1;       // every hour
  int timeline = 24;   // 1day
  
  /**
   * Allow changes after initial construction/reload/deserialization
   * @param params is argument (substring) of syscmd publish
   * syscmd archive 72 168 ftp://..  will archive each 3 days values for 1 week to ftp://...
   * syscmd publish 1 24 ftp://..  will publish each hour one day graph to ftp://...
   */
  public void parametrize(String params) {
    String[] cmdA = params.split(" ");
    if (cmdA.length < 3)
      urlpattern = "";
    for (int i = 0; i < cmdA.length; i++) {
      switch(i) {
        case 0: delay=Integer.parseInt(cmdA[i]); break;
        case 1: timeline=Integer.parseInt(cmdA[i]); break;
        case 2: urlpattern=cmdA[i]; break;
      }
    }
  }
  
  public String params() {
    StringBuilder sb = new StringBuilder();
    return sb.append(delay).append(' ').append(timeline).append(' ').append(urlpattern).toString();
  }
  
  public boolean post(String content, String host) {
    boolean ret = false;
    if (urlpattern != null && !urlpattern.isEmpty() && delay > 0 && timeline > 0) {
      if (urlpattern.startsWith("ftp://")) {
        if (!content.isEmpty()) {
          String target = urlpattern.replace("<host>", host).replace("<date>", DDF.format(new Date()));
          LOG.log(Level.FINE, "Posting {0} to {1}", new Object[]{content.substring(0, 20) + "...", target});
          CompletableFuture.supplyAsync(() -> {
            try (OutputStreamWriter osw = new OutputStreamWriter(new URL(target).openConnection().getOutputStream())) {
              int bsz = 4096;
              int start = 0;
              while (start + bsz < content.length()) {
                osw.write(content, start, bsz);
                start += bsz;
              }
              osw.write(content, start, content.length() - start);
              osw.close();
              return "Publishing OK";
            } catch (IOException ex) {
              return "Publishing error: " + ex.getMessage();
            }
          }).thenAccept(msg -> LOG.log(Level.INFO, msg))
            //.thenRun(() -> LOG.log(Level.INFO, "Publish finished"))
            .join();
        } else {
          LOG.log(Level.WARNING, "Missing content");
        }
      } else {
        LOG.log(Level.WARNING, "Invalid ftp url");
      }
    } // missing pattern: do nothing
    return ret;
  }
}
