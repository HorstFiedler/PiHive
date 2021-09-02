/* $Id: PiContextListener.java,v 1.5 2019/09/13 13:33:58 horst Exp $ */
package at.hfff.pi;

import static at.hfff.pi.PiHive.LOG;
import com.pi4j.io.gpio.GpioFactory;
import java.util.logging.Level;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/**
 * for tomcat servlet start - stop
 * @author horst
 */
public class PiContextListener implements ServletContextListener {

  @Override
  public void contextInitialized(ServletContextEvent event) {
    ServletContext ctx = event.getServletContext();
    //String name = ctx.getServletContextName();  // oh, thats not display name from web.xml
    String name = ctx.getContextPath();  // includes leading slash
    // here more init parameters ... might be retrieved
    // get/create a PI instance (singleton), configure and start mainloop
    if (PiHive.getInstance().configure(name)) {
      PiHive.startDaemon();
      LOG.log(Level.INFO, "{0} started", name);
    } else {
      LOG.log(Level.SEVERE, "{0} startup failed, check logging", name);
    }
  }

  @Override
  public void contextDestroyed(ServletContextEvent event) {
    // interrupt mainloop to force mainloop exit
    PiHive.getInstance().terminate();
    GpioFactory.getInstance().shutdown();
    LOG.log(Level.INFO, "Pi (and GPIO) released");
  }

}
