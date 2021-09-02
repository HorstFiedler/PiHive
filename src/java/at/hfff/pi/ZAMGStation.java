package at.hfff.pi;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Connection;
import org.jsoup.helper.HttpConnection;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * similar to OWMStation but allows to get more weather information by scanning
 * a ZAMG webpage for nearby location row Holder for multiple "sensors" without
 * need for calibration, .... see TAWES-Parser project for testing
 *
 * @author horst
 */
public class ZAMGStation extends WeatherStation {

  // row fields order: Location Heigth(m) Temp(°C) Humidity(%) Wind(dir, km/h) WindMax(km/h) Rain(mm) Sun(%) Pressure(hPa)
  private final NUD[] nudA = new NUD[]{
    // location 0
    // sea level heigth 1
    new NUD(new String[]{"Tws", "°C", "Temperatur"}, 2),
    new NUD(new String[]{"Hws", "%", "Luftfeuchte"}, 3),
    // windrichtung und Durchschnittsgeschwindigkeit 4
    new NUD(new String[]{"Wws", "km/h", "Windgeschwindigkiet (max)"}, 5),
    new NUD(new String[]{"Nws", "mm", "Niederschlags (1h)"}, 6),
    new NUD(new String[]{"Sws", "%", "Sonnenschein"}, 7),
    new NUD(new String[]{"Pws", "hPa", "Luftdruck (Meeresniveau)"}, 8) // using http parameters pressure at station heigth might be possible
  };

  private static final String HREF = "https://www.zamg.ac.at";
  private static final String SREF = "/cms/de/wetter/wetterwerte-analysen/steiermark";
  private static final Pattern DYNHEAD = Pattern.compile("dynPageTextHead .*");
  private static final Pattern TIME = Pattern.compile("Aktuelle Messwerte der Wetterstationen von (\\d+) Uhr");
  private static final Pattern DYNROW = Pattern.compile("dynPageTableLine\\d");
  private static final String RATTR = "Fürstenfeld";
  // Problem: JSoup connection gets "Connection reset", set (default) useragent into request
  // https://www.whoishostingthis.com/tools/user-agent/
  //private static final String UA = "Mozilla/5.0 (Windows NT 6.3; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/45.0.2454.85 Safari/537.36";

  // not reliable ??? (connection reset appears since may 8 2020) -> retry 
  private static final int MAXRETRY = 3;
  
  // enable use of tail -f /var/log/tomcat8/catalina.out, used by PiEndpoint too
  private static final Logger LOG = PiHive.LOG;

  private Optional<CompletableFuture<Document>> zamgData = Optional.empty();

  public ZAMGStation(Map<String, Sensor> sensorMap) {
    super(sensorMap);
    super.setup(nudA, getClass());
  }

  @Override
  public void trigger() {
    zamgData = Optional.of(CompletableFuture.supplyAsync(() -> {
      Connection cn = org.jsoup.Jsoup.connect(HREF).userAgent(HttpConnection.DEFAULT_UA).timeout(20000);
      Document doc = null;
      int rc = MAXRETRY;
      do {
        try {
          doc = cn.url(HREF + SREF).get();
          rc = 0;
        } catch (IOException ex) { // e.g. java.net.UnknownHostException when routers nameservice fails
          LOG.log(Level.SEVERE, "Data load from {0} failed: {1}", new Object[]{HREF + SREF, ex});
          if (ex.getMessage().equals("Connection reset")) {
            rc--;
          } else
            rc = 0;
        }
        if (rc > 0) try {
          Thread.sleep(20000);
        } catch (InterruptedException ex) {}
      } while (rc > 0);
      return doc;
    }));
    LOG.log(Level.FINE, "ZAMG async called");
  }

  /**
   * scan HTML Document
   *
   * @return true if successfull
   */
  @Override
  public boolean hasData() {
    if (zamgData.isPresent()) {
      LOG.log(Level.FINE, "ZAMG data present");
      CompletableFuture<Document> cf = zamgData.get();
      if (cf.isDone()) {
        try {
          Document doc = (Document) cf.get();
          zamgData = Optional.empty();
          // get time
          long time = Long.MIN_VALUE;
          if (doc != null) {
            for (Element h2Elem : doc.getElementsByAttributeValueMatching("class", DYNHEAD)) {
              Matcher getTime = TIME.matcher(h2Elem.text());
              if (getTime.matches()) {
                int hr = Integer.parseInt(getTime.group(1));
                Calendar cal = Calendar.getInstance();
                int hl = cal.get(Calendar.HOUR_OF_DAY);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                cal.set(Calendar.HOUR_OF_DAY, hr);

                time = cal.getTimeInMillis();
                if (hr - hl > 22) {
                  time -= 24 * 3600 * 1000; // yesterday
                }
                LOG.log(Level.FINE, " time: {0}", new Date(time));
              }
            }
            if (time != Long.MIN_VALUE) {
              // no need to search for values when no time
              for (Element trElem : doc.getElementsByAttributeValueMatching("class", DYNROW)) {
                Element aElem = trElem.child(0).child(0);
                LOG.log(Level.FINE, "A: {0}", aElem.text());
                if (RATTR.equals(aElem.ownText())) {
                  for (String name : channels) {
                    ExtSensor s = (ExtSensor) sensorMap.get(name);
                    Element tdElem = trElem.child(Integer.parseInt(s.getId()));
                    String val = tdElem.text();
                    String[] flds = val.split(" |°");  // strip unit 
                    // ATTENTION: Seen values without numeric value, unit only! -> ArrayIndexOutOfBoundsException
                    try {
                      s.setValue(new StampedNV(time, name, Double.parseDouble(flds[0])));
                    } catch (ArrayIndexOutOfBoundsException | NumberFormatException fe) {
                      LOG.log(Level.WARNING, "Unexpected table content {0} {1}", new Object[]{val, fe.getMessage()});
                    }
                  }
                }
              }
              return true;
            }
          }
        } catch (InterruptedException | ExecutionException ex) {
          LOG.log(Level.SEVERE, "", ex);
        }
      }
    }
    return false;
  }
}
