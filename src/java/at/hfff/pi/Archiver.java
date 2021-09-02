/* $Id: Archiver.java,v 1.8 2020/03/15 18:36:39 horst Exp $ */
package at.hfff.pi;

import java.io.Serializable;

/**
 * Periodical saving of log data, typically each 3days values of one week 
 * @author horst
 */
public class Archiver extends Poster implements Serializable {

  private static final long serialVersionUID = 2002L; 
 
  /**
   * send Log to target
   * @param history
   * @param host
   * @return false on error or target misses date field 
   */
  public long archive(History history, String host) {
    post(history.toString(), host);
    return delay * 3600000;
  }
}
