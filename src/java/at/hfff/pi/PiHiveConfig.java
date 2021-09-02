/* $Id: PiHiveConfig.java,v 1.1 2019/01/27 18:29:36 horst Exp $ */
package at.hfff.pi;

import at.hfff.pi.ws.PiEndpoint;
import java.util.HashSet;
import java.util.Set;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerEndpointConfig;

/**
 * Provide classes acting as websocket endpoints
 *
 * @author horst
 */
public class PiHiveConfig implements ServerApplicationConfig {

  /**
   * Just one programmatic endpoint: PiEndpoint.class 
   * @param set
   * @return just the PiEndpoint with defined path 
   */
  @Override
  public Set<ServerEndpointConfig> getEndpointConfigs(Set<Class<? extends Endpoint>> set) {
    // configuration of endpoints without using annotations
    Set<ServerEndpointConfig> result = new HashSet<>();
    if (set.contains(PiEndpoint.class)) {
      result.add(ServerEndpointConfig.Builder.create(PiEndpoint.class, "/piautomation").build());
    }
    return result;
  }

  /**
   * unused as programmatic implementation is prefered
   * @param set
   * @return 
   */
  @Override
  public Set<Class<?>> getAnnotatedEndpointClasses(Set<Class<?>> set) {
    // Deploy all WebSocket endpoints defined by annotations in the examples
    // web application. Filter out all others to avoid issues when running
    // tests on Gump
    Set<Class<?>> results = new HashSet<>();
    for (Class<?> clazz : set) {
      if (clazz.getPackage().getName().matches("at.hfff.pi.*-annotated")) {
        results.add(clazz);
      }
    }
    return results;
  }
}
