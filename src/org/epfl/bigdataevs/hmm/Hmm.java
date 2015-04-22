package org.epfl.bigdataevs.hmm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.commons.collections.list.TreeList;

import scala.Array;

/**
 * A Hidden Markov Model (HMM) built to perform analysis of theme life cycles (paper section 4). An
 * HMM can be trained using train() or used to decode a text stream using decode() ; all the other
 * steps of theme life cycles analysis should not be done in this class.
 * 
 * @author team Damien-Laurent-Sami
 *
 */
public class Hmm {

  private final int k;
  private final int n;
  private final int m;

  private TreeList outputAlphabet;

  private double[] pi;
  private double[][] a;
  private double[][] b;

  /**
   * The basic hmm constructor.
   * 
   * 
   * @param k
   *          the number of transcollection-themes considered, the HMM will have k+1 states
   * @param wordsLexicon
   *          a rdd containing all the words in the vocabulary set (only once)
   * @param models
   *          the k+1 models : background model (index 0) and the k trans-collection theme
   *          probabilities
   */
  public Hmm(int k, JavaRDD<String> wordsLexicon,
          JavaPairRDD<Integer, JavaPairRDD<String, Double>> models) {
    this.k = k;
    n = k + 1;
    outputAlphabet = new TreeList(wordsLexicon.collect());

    m = outputAlphabet.size();
    pi = new double[n];
    a = new double[n][n];
    b = new double[n][m];
    // TODO initialize b using the models provided as arguments
    for (Entry<Integer, JavaPairRDD<String, Double>> entry : models.collectAsMap().entrySet()) {
      int i = entry.getKey();
      for (Entry<String, Double> pair : entry.getValue().collectAsMap().entrySet()) {
        b[i][outputAlphabet.indexOf(pair.getKey())] = pair.getValue();
      }
    }

  }

  /**
   * Dummy constructor for test purposes.
   * 
   */
  public Hmm(List<String> outputAlphabet, double[] pi, double[][] a, double[][] b) {
    n = a.length;
    k = n - 1;
    m = outputAlphabet.size();
    this.outputAlphabet = new TreeList(outputAlphabet);
    this.pi = pi;
    this.a = a;
    this.b = b;

  }
  
  /**
   * Raw constructor for an HMM.
   * Only use this constructor if using raw train/decode/generate sequence functions
   * @param n Number of hidden states.
   * @param m Number of observable states.
   * @param pi Initial probability distribution
   * @param a Hidden states transition probability matrix
   * @param b Observed state probability matrix.
   */
  public Hmm(int n, int m, double[] pi, double[][] a, double[][] b) {
    this.n = n;
    this.k = n - 1;
    this.m = m;
    this.pi = pi;
    this.a = a;
    this.b = b;
  }

  /**
   * This method trains the HMM by performing the Baum-Welch algorithm.
   * 
   * @param fullArticleStream
   *          the full text of the concatenated articles
   */
  public void train(JavaRDD<String> fullArticleStream) {
    /*
     * First convert the fullAtircleStream into a
     * an array of indices as used in the observation probability
     * matrix
     */
    List<String> wordSequence = fullArticleStream.collect();
    int sequenceLength = wordSequence.size();
    
    int[] observedSequence = new int[sequenceLength];
    
    for ( int wordIndex = 0; wordIndex < sequenceLength; wordIndex++ ) {
      observedSequence[wordIndex] = outputAlphabet.indexOf(wordSequence.get(wordIndex));
    }
    
    // And then do the training on this raw sequence.
    rawTrain(observedSequence, sequenceLength);
  }
  
  /**
   * This method trains the HMM by performing the Baum-Welch algorithm.
   * 
   * @param observedSequence
   *          the list of observed output states indexes.
   */
  public void rawTrain(int[] observedSequence, int sequenceLength) {

    // Variables in which we store the next iteration results
    double[] piStar = new double[n];
    double[][] aaStar = new double[n][n];
    
    // variable tracking convergence
    double prevLogLikelihood = Double.NEGATIVE_INFINITY;
    // threshold for convergence
    // TODO propose this parameter as an argument
    double likelihoodThreshold = 1.0;
    
    // Temporary variables used in every iteration
    double[] alphasScales = new double[ sequenceLength ];
    double[] alphas = new double[n * sequenceLength];
    double[] betas = new double[n * sequenceLength];
    double[] gammas = new double[n];
    double[] gammasSums = new double[n];
    
    // Iterate until convergence of the transition probabilities
    int maxSteps = 100;
    for ( int iterationStep = 0; iterationStep < maxSteps; iterationStep++ ) {
      System.out.println("Iteration " + iterationStep);
      
      /*
       * Generate all the alphas
       */
      // initialize the first alphas
      {
        double sum = 0.0;
        for ( int i = 0; i < n; i++ ) {
          double value = pi[i] * b[i][observedSequence[0]];
          alphas[0 * n + i] = value;
          sum += value;
        }
        
        // rescale
        double scale = 1.0 / sum;
        alphasScales[0] = scale;
        
        for ( int i = 0; i < n; i++) {
          alphas[0 * n + i] *= scale; 
        }
      }
      
      // compute the other alphas
      for ( int t = 1; t < sequenceLength; t++ ) {
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
          double res = 0.0;
          for (int j = 0; j < n; j++) {
            res += (alphas[(t - 1) * n + j] * a[j][i]);
          }
          double value = (res * b[i][observedSequence[t]]);
          alphas[t * n + i] = value;
          sum += value;
        }
        
        // rescale
        double scale = 1.0 / sum;
        alphasScales[t] = scale;

        for ( int i = 0; i < n; i++) {
          alphas[t * n + i] *= scale; 
        }
      }
      
      /*
       * Generate all the betas coefficients
       */
      for (int stateIndex = 0; stateIndex < n; stateIndex++) {
        betas[(sequenceLength - 1) * n + stateIndex] = 1.0d;
      }

      for (int t = sequenceLength - 1; t >= 1; t--) {
        for (int i = 0; i < n; i++) {
          double res = 0.0;
          for (int j = 0; j < n; j++) {
            res += (betas[t * n + j] * a[i][j]
                   * b[j][observedSequence[t]] * alphasScales[t - 1]);
          }

          betas[(t - 1) * n + i] = res;
        }
      }
      
      // reset temporary variables
      Arrays.fill(gammasSums, 0.0d);
      for ( int stateIndex = 0; stateIndex < n; stateIndex++ ) {
        Arrays.fill(aaStar[stateIndex], 0.0);
      }
      
      // as we don't need to update b, we can stop at
      // sequenceLength-1
      for ( int t = 0; t < sequenceLength - 1; t++ ) {
        
        // compute the terms alpha(i,t)*beta(i,t) and incrementally the sum of them
        for (int i = 0; i < n; i++) {
          double tempVal = alphas[t * n + i] * betas[t * n + i];
          gammas[i] = tempVal;
        }

        // compute gamma(i,t), and incrementally gamma_sums(i)
        for (int i = 0; i < n; i++) {
          double tempVal = gammas[i] / alphasScales[t];
          gammas[i] = tempVal;
          gammasSums[i] += tempVal;
        }

        // we have now gamma(i,t) in gammas[], and sum( k, alpha(k, t)*beta(k, t) ) in denGamma */
        /* compute khi(i,j) incrementally, put it in aaStar */
        if (t != sequenceLength - 1) {
          for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
              double khi = (alphas[t * n + i] * a[i][j] * betas[(t + 1) * n + j])
                      * b[j][observedSequence[t + 1]];
              aaStar[i][j] += khi;
            }
          }
        }
        /* copy in Pi_star if that's the moment */
        if (t == 0) {
          System.arraycopy(gammas, 0, piStar, 0, n);
        }
      }
      
      // Renormalize aaStar
      for (int i = 0; i < n; i++) {
        double sum = 0.0;
        for (int j = 0; j < n; j++) {
          sum += aaStar[i][j];
        }
        if ( sum > 0.0 ) {
          for (int j = 0; j < n; j++) {
            aaStar[i][j] /= sum;
          }
        }
      }
      
      // Renormalize piStar
      double sum = 0.0;
      for (int i = 0; i < n; i++ ) {
        sum += piStar[i];
      }
      if ( sum > 0.0 ) {
        for ( int i = 0; i < n; i++ ) {
          piStar[i] /= sum;
        }
      }
      
      // Check convergence here
      double logLikelihood = 0.0;
      for ( int t = 0; t < sequenceLength; t++ ) {
        logLikelihood -= Math.log(alphasScales[t]);
      }
      
      // Copy back piStar and aaStar
      double[] temp1 = pi;
      pi = piStar;
      piStar = temp1;
      
      double[][] temp2 = a;
      a = aaStar;
      aaStar = temp2;
      
      // break when both criterion have been  met
      if ( Math.abs(logLikelihood - prevLogLikelihood) < likelihoodThreshold ) {
        //break;
      }
      
      prevLogLikelihood = logLikelihood;
    }
  }
  
  
  
  /** This methods is the iterative version (for test purposes)
   *  of a new and beautiful parallel version of Baum-Welch.
   * 
   * @param observedSequence the sequence of output symbols observed
   */
  public void rawParalellTrain(int[] observedSequence, double likelihoodThreshold) {
    
    int sequenceLength = observedSequence.length;
    
    // Variables in which we store the next iteration results
    double[] piStar = new double[n];
    double[][] aaStar = new double[n][n];
    
    // variable tracking convergence
    double prevLogLikelihood = Double.NEGATIVE_INFINITY;
    
    // Temporary variables used in every iteration
    SquareMatrix[] taTildes = new SquareMatrix[sequenceLength];
    SquareMatrix[] tbTildes = new SquareMatrix[sequenceLength];
    ScanLeft<SquareMatrix> alphasScanner = new ScanLeft<SquareMatrix>(sequenceLength);
    ScanRight<SquareMatrix> betasScanner = new ScanRight<SquareMatrix>(sequenceLength);
    Vector[] alphasHat = new Vector[sequenceLength];
    Vector[] betasHat = new Vector[sequenceLength];
    Vector[] alphasBar = new Vector[sequenceLength];
    double[] alphasScales = new double[ sequenceLength ]; //c_t
    double[] gammas = new double[n];
    double[] gammasSums = new double[n];
    
    // Iterate until convergence of the transition probabilities
    int maxSteps = 100;
    for ( int iterationStep = 0; iterationStep < maxSteps; iterationStep++ ) {
      System.out.println("Iteration " + iterationStep);
      
     //1. initialise the TA t-1->t
      final class TaTildeInitializerMapper implements MapOperator<SquareMatrix> {

        int n;
        double[] pi;
        double[][] b;
        int[] observedSequence;
        
        public TaTildeInitializerMapper(
                int n,
                double[] pi,
                double[][] b,
                int[] observedSequence ) {
          this.n = n;
          this.pi = pi;
          this.b = b;
          this.observedSequence = observedSequence;
        }
        
        @Override
        public SquareMatrix apply(int index, SquareMatrix matrix, SquareMatrix out) {
          if ( index == 0 ) {
            for (int i = 0; i < n; i++) {
              out.elements[i * n + i] = pi[i] * b[i][observedSequence[0]];
            }
            double norm0 = out.rawNorm1();
            out.scalarDivide(norm0);
          } else  {
            for (int i = 0; i < n; i++) {
              for (int j = 0; j < n; j++) {
                out.elements[i * n + j] = a[j][i] * b[i][observedSequence[index]];
              }
            }
            out.scalarDivide(out.rawNorm1());
          }
          return out;
        }
      }
      
      {
        for ( int t = 0; t < sequenceLength; t++ ) {
          taTildes[t] = new SquareMatrix(n);
        }
        
        // TODO: need to pay attention to the fact that we don't set alphaScales[0] anymore
        new Map<SquareMatrix>().map(
                taTildes,
                new TaTildeInitializerMapper(
                        n,
                        pi,
                        b,
                        observedSequence));
      }
      
      //2. compute the TA 0->t
      alphasScanner.scan(
              taTildes,
              new RenormalizedReverseMatrixMulOperator(),
              new SquareMatrix(n).setIdentity() );
      
      // mapper to compute transformed vectors
      final class VectorTransformer implements MapOperator<Vector> {

        int n;
        SquareMatrix[] transformMatrices;
        
        public VectorTransformer( int n, SquareMatrix[] transformMatrices ) {
          this.n = n;
          this.transformMatrices = transformMatrices;
        }
        
        @Override
        public Vector apply(int index, Vector vec, Vector out) {
          for (int i = 0; i < n; i++) {
            double aux = 0.0;
            for (int h = 0; h < n; h++) {
              aux += transformMatrices[index].elements[i * n + h];
            }
            out.elements[i] = aux;
          }
          return out;
        }
        
      }
      //3. compute alphaHat(t)
      {
        for ( int t = 0; t < sequenceLength; t++ ) {
          alphasHat[t] = new Vector(n);
        }
         
        new Map<Vector>().map(alphasHat, new VectorTransformer(n, taTildes));
      }
      
      //4. compute alphaBar(t)
      {
        final class AlphasBarMapper implements MapOperator<Vector> {

          int n;
          double[] pi;
          double[][] a;
          double[][] b;
          int[] observedSequence;
          Vector[] alphasHat;
          
          public AlphasBarMapper(
                  int n,
                  double[] pi,
                  double[][] a,
                  double[][] b,
                  int[] observedSequence,
                  Vector[] alphasHat ) {
            this.n = n;
            this.pi = pi;
            this.a = a;
            this.b = b;
            this.observedSequence = observedSequence;
            this.alphasHat = alphasHat;
          }
          
          @Override
          public Vector apply(int index, Vector vec, Vector out) {
            if ( index > 0 ) {
              for (int i = 0; i < n; i++) {
                double res = 0.0;
                for (int h = 0; h < n; h++) {
                  res += b[i][observedSequence[index]] * a[h][i]
                          * alphasHat[(index - 1)].elements[h];
                }
                out.elements[i] = res;
              }
            } else {
              for (int i = 0; i < n; i++) {
                out.elements[i] = pi[i] * b[i][observedSequence[0]];
              }
            }
            return out;
          }
          
        }
        
        for ( int t = 0; t < sequenceLength; t++ ) {
          alphasBar[t] = new Vector(n);
        }
        
        new Map<Vector>().map(
                alphasBar,
                new AlphasBarMapper(
                        n,
                        pi,
                        a,
                        b,
                        observedSequence,
                        alphasHat));
      }
      
      //5. compute c_t i.e. alphasScales
      {
        final class ScalesMapper implements MapOperator<Vector> {

          int n;
          Vector[] alphasHat;
          Vector[] alphasBar;
          double[] alphasScales;
          
          public ScalesMapper(
                  int n,
                  Vector[] alphasHat,
                  Vector[] alphasBar,
                  double[] alphasScales ) {
            this.n = n;
            this.alphasHat = alphasHat;
            this.alphasBar = alphasBar;
            this.alphasScales = alphasScales;
          }
          
          @Override
          public Vector apply(int index, Vector element, Vector out) {
            double sumBar = 0.0;
            for (int i = 0; i < n; i++) {
              sumBar += alphasBar[index].elements[i];
            }
            alphasScales[index] = 1.0 / sumBar;
            return element;
          }
          
        }
        
        new Map<Vector>().map(
                alphasHat,
                new ScalesMapper(
                        n,
                        alphasHat,
                        alphasBar,
                        alphasScales));
      }
      
      /*
       * Generate all the betas coefficients
       */
      {
        final class TbTildesInitializer implements MapOperator<SquareMatrix> {

          int n;
          double[][] a;
          double[][] b;
          int[] observedSequence;
          double[] alphasScales;
          
          public TbTildesInitializer(
                  int n,
                  double[][] a,
                  double[][] b,
                  int[] observedSequence,
                  double[] alphasScales) {
            this.n = n;
            this.a = a;
            this.b = b;
            this.observedSequence = observedSequence;
            this.alphasScales = alphasScales;
          }
          
          @Override
          public SquareMatrix apply(int t, SquareMatrix element, SquareMatrix out) {
            //1. initialise the TB t+1->t
            int sequenceLength = observedSequence.length;
            if ( t == sequenceLength - 1 ) {
              for (int i = 0; i < n; i++) {
                out.elements[i * n + i] = alphasScales[sequenceLength - 1];
              }
            } else {
              for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                  out.elements[i * n + j] =
                          a[i][j] * b[j][observedSequence[t + 1]] * alphasScales[t];
                }
              }
            }
            return out;
          }
          
        }

        for (int t = 0;t < sequenceLength;t++) {
          tbTildes[t] = new SquareMatrix(n);
        }

        new Map<SquareMatrix>().map(
                tbTildes,
                new TbTildesInitializer(
                        n,
                        a,
                        b,
                        observedSequence,
                        alphasScales));
      }
      
      //2. compute the TB sL-1->t
      betasScanner.scan(
              tbTildes,
              new MatrixMultiplicationOperator(),
              new SquareMatrix(n).setIdentity());
      
      //3. compute betasHat(t)
      for ( int t = 0; t < sequenceLength; t++ ) {
        betasHat[t] = new Vector(n);
      }
      
      new Map<Vector>().map(betasHat, new VectorTransformer(n, tbTildes));

      // reset temporary variables
      Arrays.fill(gammasSums, 0.0d);
      for ( int stateIndex = 0; stateIndex < n; stateIndex++ ) {
        Arrays.fill(aaStar[stateIndex], 0.0);
      }
      
      // as we don't need to update b, we can stop at
      // sequenceLength-2
      for ( int t = 0; t < sequenceLength - 2; t++ ) {
        
        // compute the terms alpha(i,t)*beta(i,t) and incrementally the sum of them
        for (int i = 0; i < n; i++) {
          double tempVal = alphasHat[t].elements[i] * betasHat[t].elements[i];
          gammas[i] = tempVal;
        }

        // compute gamma(i,t), and incrementally gamma_sums(i)
        for (int i = 0; i < n; i++) {
          double tempVal = gammas[i] / alphasScales[t];
          gammas[i] = tempVal;
          gammasSums[i] += tempVal;
        }

        // we have now gamma(i,t) in gammas[], and sum( k, alpha(k, t)*beta(k, t) ) in denGamma */
        /* compute khi(i,j) incrementally, put it in aaStar */
        for (int i = 0; i < n; i++) {
          for (int j = 0; j < n; j++) {
            double khi = (alphasHat[t].elements[i] * a[i][j] * betasHat[(t + 1)].elements[j])
                    * b[j][observedSequence[t + 1]];
            aaStar[i][j] += khi;
          }
        }
        /* copy in Pi_star if that's the moment */
        if (t == 0) {
          System.arraycopy(gammas, 0, piStar, 0, n);
        }
      }
      
      // Renormalize aaStar
      for (int i = 0; i < n; i++) {
        double sum = 0.0;
        for (int j = 0; j < n; j++) {
          sum += aaStar[i][j];
        }
        if ( sum > 0.0 ) {
          for (int j = 0; j < n; j++) {
            aaStar[i][j] /= sum;
          }
        }
      }
      
      // Renormalize piStar
      double sum = 0.0;
      for (int i = 0; i < n; i++ ) {
        sum += piStar[i];
      }
      if ( sum > 0.0 ) {
        for ( int i = 0; i < n; i++ ) {
          piStar[i] /= sum;
        }
      }
      
      // Check convergence here
      double logLikelihood = 0.0;
      for ( int t = 0; t < sequenceLength; t++ ) {
        logLikelihood -= Math.log(alphasScales[t]);
      }
      
      // Copy back piStar and aaStar
      double[] temp1 = pi;
      pi = piStar;
      piStar = temp1;
      
      double[][] temp2 = a;
      a = aaStar;
      aaStar = temp2;
      
      // break when both criterion have been  met
      if ( Math.abs(logLikelihood - prevLogLikelihood) < likelihoodThreshold ) {
        //break;
      }
      
      prevLogLikelihood = logLikelihood;
    }
    
  }

  /**
   * This method associates a state of the HMM to each word of the stream using Viterbi algorithm.
   * 
   * @param fullArticleStream
   *          the full text of the concatenated articles
   * @return the sequence of HMM states associated with the stream : each state is represented by an
   *         integer between 0 and k (0 for the background model)
   */
  public JavaRDD<Integer> decode(JavaRDD<String> fullArticleStream) {
    // TODO implement decode

    return null;

  }

  /**
   * Single process version of decode (without spark).
   * 
   * @param fullArticleStream
   *          the full text of the concatenated articles
   * @return the array representing the sequence of states
   */
  public int[] decode(List<String> fullArticleStream) {
    int T = fullArticleStream.size();
    int[] rawObservedSequence = new int[T];
    for ( int t = 0; t < T; t++ ) {
      rawObservedSequence[t] = outputAlphabet.indexOf(fullArticleStream.get(t));
    }
    return rawDecode(rawObservedSequence);
  }

  /**
   * Single process version of decode (without spark).
   * 
   * @param rawObservedSequence
   *          the observed states sequence
   * @return the array representing the sequence of states
   */
  public int[] rawDecode(int[] rawObservedSequence) {
    int T = rawObservedSequence.length;
    double[][] dynamicValue = new double[n][T];
    int[][] dynamicState = new int[n][T];
    
    int index = rawObservedSequence[0];
    for (int i = 0; i < n; i++) { // initialization
      dynamicValue[i][0] = Math.log(pi[i] * b[i][index]);
    }

    for ( int t = 1; t < T;t++) {
      int observedState = rawObservedSequence[t];
      
      for (int i = 0; i < n; i++) {
        double max = dynamicValue[0][t - 1] + Math.log(a[0][i] * b[i][observedState]);
        int argmax = 0;
        for (int j = 1; j < n; j++) {
          double current = dynamicValue[j][t - 1] + Math.log(a[j][i] * b[i][observedState]);
          if (current > max) {
            max = current;
            argmax = j;
          }
        }
        dynamicValue[i][t] = max;
        dynamicState[i][t] = argmax;
      }
    }

    int[] states = new int[T];
    double max = dynamicValue[0][T - 1];
    int argmax = 0;
    double current = 0D;
    for (int j = 1; j < n; j++) {
      current = dynamicValue[j][T - 1];
      if (current > max) {
        max = current;
        argmax = j;
      }
    }
    states[T - 1] = argmax;
    for (int w = T - 2; w > 0; w--) {
      states[w - 1] = dynamicState[states[w]][w];
    }

    return states;

  }
  
  /**
   * Generates an observation sequence of length "length" given a fully known HMM.
   * 
   * @param length
   *          the length of the observation sequence to be generated
   * @return the observation sequence i.e. a list of outputs
   */
  public List<String> generateObservationSequence(int length) {
    List<String> sequence = new ArrayList<String>(length);
    int[] rawSequence = generateRawObservationSequence(length);
    
    for ( int t = 0; t < length; t++ ) {
      sequence.add((String) outputAlphabet.get(rawSequence[t]));
    }
    return sequence;
  }
  
  /**
   * Generates a raw observation sequence of length "length" given a fully known HMM.
   * 
   * @param length
   *          the length of the observation sequence to be generated
   * @return the raw observation sequence i.e. a list of observation states indexes.
   */
  public int[] generateRawObservationSequence(int length) {
    int[] sequence = new int[length];
    double initRandom = Math.random();
    int initialState = -1;
    while (initRandom > 0.0 && initialState < (n - 1)) {
      initialState++;
      initRandom -= pi[initialState];
    }
    int currentState = initialState;
    for (int t = 0; t < length; t++) {
      // System.out.print(currentState+"     ");
      double randOutput = Math.random();
      double randTransition = Math.random();
      int outIndex = -1;
      while (randOutput > 0.0 && outIndex < (m - 1)) {
        outIndex++;
        randOutput -= b[currentState][outIndex];
      }
      //System.out.print(outIndex+" "+b[currentState][outIndex]+";");
      sequence[t] = outIndex;
      int nextState = -1;
      while (randTransition > 0.0 && nextState < (n - 1)) {
        nextState++;
        randTransition -= a[currentState][nextState];
      }
      currentState = nextState;
      
    }
    
    System.out.println();
    System.out.println("done generating sequence");

    return sequence;
  }

  public int getK() {
    return k;
  }

  public List<String> getOutputAlphabet() {
    return outputAlphabet;
  }

  public int getN() {
    return n;
  }
  
  public int getM() {
    return m;
  }
  
  public double[] getPi() {
    return pi;
  }
  
  public double[][] getA() {
    return a;
  }
  
  public double[][] getB() {
    return b;
  }
}
