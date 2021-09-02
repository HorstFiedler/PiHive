/* $Id: Publisher.java,v 1.11 2020/03/15 18:36:39 horst Exp $ */
package at.hfff.pi;

import java.io.Serializable;

/**
 * Periodical posting log data as graphics, typically each hour for one week
 * Post configuration, serializable (allthough superclass serializable would be sufficient)
 * 
 * @author horst
 */
public class Publisher extends Poster implements Serializable {
  
  /* as reported by MotoG3 (how to expand fullscreen ???)
  int width = 360;    // default width
  int height = 512;  // default height 
  */
  int width = 720;    // better suited, allows zooming
  int height = 1024;  
  
  // explicit to allow nonstructural changes
  private static final long serialVersionUID = 1002L; 
  
  /**
   * periodical publish request
   * @param instance as svg generator
   * @param source  e.g. hostname
   * @return delay till next attempt in milliseconds
   */
  public long publish(PiHive instance, String source) {
    post(instance.getSVG(width, height, timeline), source);
    return delay * 3600000;
  }
}
