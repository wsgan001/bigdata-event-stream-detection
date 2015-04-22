package org.epfl.bigdataevs;

import org.epfl.bigdataevs.em.Theme;

public class EvolutionaryTransition{
  public Theme theme1;
  public Theme theme2;
  public double divergence;
  
  /**
   * @author antoinexp & lfaucon
   * 
   * @param t1 The first theme (chronological order)
   * @param t2 The second theme (chronological order)
   * @param divergence The Kullback divergence D(t1||t2). It shows the strength of the link
   *     between theme1 and theme2
   */
  public EvolutionaryTransition(Theme t1, Theme t2, double divergence) {
    this.theme1 = t1;
    this.theme2 = t2;
    this.divergence = divergence;
  }
}