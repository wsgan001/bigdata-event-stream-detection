package org.epfl.bigdataevs.hmm;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.storage.StorageLevel;
import org.epfl.bigdataevs.executables.Parameters;

import scala.Tuple2;
import scala.Tuple3;



/**
 * A Hidden Markov Model (HMM) built to perform analysis of theme life cycles (paper section 4). An
 * HMM can be trained using train() or used to decode a text stream using decode() ; all the other
 * steps of theme life cycles analysis should not be done in this class.
 * 
 * @author Damien-Laurent
 *
 */
public class Hmm2 implements Serializable {
  private static final long serialVersionUID = 1L;
  
  private final int N;
  private final int M;

  private double[] pi;
  private double[][] a;
  private double[][] b;
  
  /**
   * Raw constructor for an HMM.
   * Only use this constructor if using raw train/decode/generate sequence functions
   * @param n Number of hidden states.
   * @param m Number of observable states.
   * @param pi Initial probability distribution
   * @param a Hidden states transition probability matrix
   * @param b Observed state probability matrix.
   */
  public Hmm2(
          int n,
          int m,
          double[] pi,
          double[][] a,
          double[][] b) {
    this.N = n;
    this.M = m;
    this.pi = pi;
    this.a = a;
    this.b = b;
  }
  
  /**
   * This method associates a state of the HMM to each word of the stream using Viterbi algorithm.
   * 
   * @param sc JavaSparkContext to use
   * @param wordStreamRdd
   *          the full text of the concatenated articles as (index, word index)
   * @return the sequence of HMM states associated with the stream : each state is represented by an
   *         integer between 0 and k (0 for the background model)
   */
  public JavaPairRDD<Integer, Integer> decode(
          JavaSparkContext sc,
          JavaRDD<Tuple2<Integer, Integer>> wordStreamRdd ) {
    final int blockSize = Parameters.ViterbiBlockSize;
    final int T = (int) wordStreamRdd.count();
    final int numBlocks = (T + (blockSize - 1)) / blockSize;
    
    double[] prevProbabilities = new double[N];
    double[] curProbabilities = new double[N];
    int[] backPointerBlock = new int[blockSize * N];
    
    JavaRDD<Tuple3<Integer, Integer, Integer>> wordStreamWithBlockIdRdd =
            wordStreamRdd.map( new Function<Tuple2<Integer, Integer>, Tuple3<Integer, Integer, Integer>>() {
              private static final long serialVersionUID = 1L;

              @Override
              public Tuple3<Integer, Integer, Integer> call(Tuple2<Integer, Integer> arg0)
                      throws Exception {
                int blockId = arg0._1 / blockSize;
                return new Tuple3<Integer, Integer, Integer>(blockId, arg0._1, arg0._2);
              }
              
            });
    
    JavaPairRDD<Integer, Iterable<Tuple3<Integer, Integer, Integer>>> groupedWordBlocksRdd =
            wordStreamWithBlockIdRdd.groupBy( new Function<Tuple3<Integer, Integer, Integer>, Integer>() {
              private static final long serialVersionUID = 2L;

              @Override
              public Integer call(Tuple3<Integer, Integer, Integer> arg0) throws Exception {
                return arg0._1();
              }
              
            });
    
    JavaPairRDD<Integer, int[]> wordBlocksRdd =
            groupedWordBlocksRdd.mapValues(new Function<Iterable<Tuple3<Integer, Integer, Integer>>, int[]>() {
              private static final long serialVersionUID = 3L;

              @Override
              public int[] call(Iterable<Tuple3<Integer, Integer, Integer>> arg0) throws Exception {
                int blockId = arg0.iterator().next()._1();
                int blockStart = blockId * blockSize;
                int blockEnd = Math.min( (blockId + 1) * blockSize, T);
                int trueBlockSize = (blockEnd - blockStart);
                
                int realBlockSize = 0;
                for ( Tuple3<Integer, Integer, Integer> tuple : arg0 ) {
                  realBlockSize++;
                }
                
                int[] observedBlock;
                if ( blockId == (numBlocks - 1) ) {
                  observedBlock = new int[blockEnd - blockStart];
                  if ( realBlockSize != (blockEnd - blockStart) ) {
                    System.out.println("Block sizes don't match: real " + realBlockSize
                            + " assumed " + trueBlockSize);
                  }
                } else {
                  observedBlock = new int[blockSize + 1];
                  if ( realBlockSize != blockSize + 1 ) {
                    System.out.println("Block sizes don't match: real " + realBlockSize
                            + " assumed " + (blockSize + 1));
                  }
                }
                
                for ( Tuple3<Integer, Integer, Integer> tuple : arg0 ) {
                  observedBlock[ tuple._2() - blockStart] = tuple._3();
                }
                return observedBlock;
              }
            });
    
    JavaPairRDD<Integer, int[]> backPointerBlocksRdd = null;
    
    class BlockFilter implements Function<Tuple2<Integer, int[]>, Boolean> {
      private static final long serialVersionUID = 4L;
    
      int block;
      
      public BlockFilter( int block ) {
        this.block = block;
      }
      
      @Override
      public Boolean call(Tuple2<Integer, int[]> arg0) throws Exception {
        return (arg0._1 == this.block);
      }
    }
    
    // up-phase
    for ( int bi = 0; bi < numBlocks; bi++ ) {
      // compute block information
      final int blockStart = bi * blockSize;
      final int blockEnd = Math.min( (bi + 1) * blockSize, T);
      final int trueBlockSize = (blockEnd - blockStart);
      
      // collect word block
      JavaPairRDD<Integer, int[]> wordBlockRdd = wordBlocksRdd.filter(new BlockFilter(bi));
      List<Tuple2<Integer, int[]>> wordBlockList = wordBlockRdd.collect();
      
      if ( wordBlockList.size() != 1 ) {
        System.out.println("Bad word block list size: " + wordBlockList.size() );
      }
      
      int[] wordBlock = wordBlockList.get(0)._2;
      // do the initialization if necessary
      int start = 0;
      if ( bi == 0 ) {
        start = 1;
        for ( int i = 0; i < N; i++ ) {
          int index = wordBlock[0];
          prevProbabilities[i] = Math.log(pi[i] * b[i][index]);
        }
      }
      
      // compute current probabilities, choose best path,
      // write back pointers for all elements in this block
      for ( int bt = start;  bt < trueBlockSize; bt++ ) {
        int observedState = wordBlock[bt];
        
        for ( int i = 0; i < N; i++ ) {
          double maxProb = prevProbabilities[0] + Math.log(a[0][i] * b[i][observedState]);
          int maxIndex = 0;
          for ( int j = 1; j < N; j++ ) {
            double curProb = prevProbabilities[j] + Math.log(a[j][i] * b[i][observedState]);
            if ( curProb > maxProb ) {
              maxProb = curProb;
              maxIndex = j;
            }
          }
          backPointerBlock[ bt * N + i ] = maxIndex;
          curProbabilities[i] = maxProb;
        }
        
        // swap arrays
        double[] temp = prevProbabilities;
        prevProbabilities = curProbabilities;
        curProbabilities = temp;
      }
      
      // put this block into the Rdd
      // we make sure to be super safe by copying the block pointers
      int[] backPointerBlockCopy = new int[blockSize * N];
      for ( int bt = 0; bt < trueBlockSize; bt++ ) {
        for ( int i = 0;  i < N; i++) {
          backPointerBlockCopy[ bt * N + i ] = backPointerBlock[ bt * N + i];
        }
      }

      List<Tuple2<Integer, int[]>> backPointerBlockList = new ArrayList<Tuple2<Integer, int[]>>(1);
      backPointerBlockList.add(new Tuple2<Integer, int[]>(bi, backPointerBlockCopy));
      
      JavaPairRDD<Integer, int[]> backPointerBlockRdd = sc.parallelizePairs(backPointerBlockList);
      
      // aggregate this block to the other blocks in the rdd
      if ( backPointerBlocksRdd == null ) {
        backPointerBlocksRdd = backPointerBlockRdd;
      } else {
        backPointerBlocksRdd = backPointerBlocksRdd.union(backPointerBlockRdd);
      }
    }
    
    JavaPairRDD<Integer, Integer> decodedStatesRdd = null;
    int bestState = -1;
    // lastly, walk the back-pointers
    for ( int bi = numBlocks - 1; bi >= 0; bi-- ) {
      // compute block information
      final int blockStart = bi * blockSize;
      final int blockEnd = Math.min( (bi + 1) * blockSize, T);
      final int trueBlockSize = (blockEnd - blockStart);
      
      // get the correct back pointer block
      JavaPairRDD<Integer, int[]> backPointerBlockRdd =
              backPointerBlocksRdd.filter(new BlockFilter(bi));
      
      List<Tuple2<Integer, int[]>> backPointerBlockList = backPointerBlockRdd.collect();
      
      if ( backPointerBlockList.size() != 1 ) {
        System.out.println("Bad back pointer block list size: " + backPointerBlockList.size());
      }
      
      backPointerBlock = backPointerBlockList.get(0)._2;
      List<Tuple2<Integer, Integer>> statesList =
              new ArrayList<Tuple2<Integer, Integer>>(trueBlockSize);
      
      // initialize best (last) state if necessary
      if ( bi == (numBlocks - 1) ) {
        double maxProb = prevProbabilities[0]; 
        int maxIndex = 0;
        
        for ( int i = 1; i < N; i++ ) {
          double curProb = prevProbabilities[i];
          if ( curProb > maxProb ) {
            maxProb = curProb;
            maxIndex = i;
          }
        }
        
        bestState = maxIndex;
        statesList.add( new Tuple2<Integer, Integer>(blockEnd - 1, bestState) );
      }
      
      // walk the pointers of the block
      int start = 0;
      if ( bi == 0 ) {
        start = 1;
      }
      
      for ( int bt = trueBlockSize - 1; bt >= start; bt-- ) {
        bestState =  backPointerBlock[bt * N + bestState];
        statesList.add( 0, new Tuple2<Integer, Integer>(bt + blockStart - 1, bestState));
      }
      
      // aggregate the list of states
      JavaPairRDD<Integer, Integer> decodedStatesBlockRdd = sc.parallelizePairs(statesList);
      if ( decodedStatesRdd == null) {
        decodedStatesRdd = decodedStatesBlockRdd;
      } else {
        decodedStatesRdd = decodedStatesRdd.union(decodedStatesBlockRdd);
      }
    }
    
    // return the fully aggregated decoded state list
    return decodedStatesRdd;
  }
  
  /**
   * Perform training on a spark Rdd observation sequence using either
   * the spark version or the sequential algorithm.
   * @param sc Spark context to use
   * @param observedSequenceRdd Rdd containing the sequence (seq index, word index)
   * @param piThreshold Threshold on pi
   * @param aaThreshold  Threshold on a
   * @param maxIterations Max number of iterations
   */
  public void rawTrain(
          JavaSparkContext sc,
          JavaRDD<Tuple2<Integer, Integer>> observedSequenceRdd,
          double piThreshold,
          double aaThreshold,
          int maxIterations) {
    long sequenceLength = observedSequenceRdd.count();
    long cost = sequenceLength * N * N;
    if ( cost > 1000L * 1000L * 1000L && !Parameters.forceSequentialBW) {
      rawSparkTrain(sc, observedSequenceRdd, piThreshold, aaThreshold, maxIterations);
    } else {
      List<Tuple2<Integer, Integer>> observedSequenceList =
              observedSequenceRdd.collect();
      
      // sort observed sequence
      Collections.sort(observedSequenceList, new Comparator<Tuple2<Integer, Integer>>() {
        @Override
        public int compare(Tuple2<Integer, Integer> index1,
                Tuple2<Integer, Integer> index2) {
          return index1._1.compareTo(index2._1);
        }
      });
      
      int[] observedSequence = new int[(int)sequenceLength];
      
      // copy observation sequence
      for ( Tuple2<Integer, Integer> tuple : observedSequenceList ) {
        observedSequence[tuple._1] = tuple._2;
      }
      rawSequentialTrain(observedSequence, piThreshold, aaThreshold, maxIterations);
    }
  }
  
  /**
   * Trains the HMM using a sequential forward-backward Baum-Welch
   * algorithm.
   * 
   * @param observedSequence
   *          the list of observed output states indexes.
   * @param piThreshold Threshold on pi
   * @param aaThreshold  Threshold on a
   * @param maxIterations Max number of iterations
   */
  public void rawSequentialTrain(
          int[] observedSequence,
          double piThreshold,
          double aaThreshold,
          int maxIterations) {

    int sequenceLength = observedSequence.length;
    // Variables in which we store the next iteration results
    double[] piStar = new double[N];
    double[][] aaStar = new double[N][N];
    
    // Temporary variables used in every iteration
    double[] alphasScales = new double[ sequenceLength ];
    double[] alphas = new double[N * sequenceLength];
    double[] betas = new double[N * sequenceLength];
    
    // Iterate until convergence of the transition probabilities
    int maxSteps = maxIterations;
    for ( int iterationStep = 0; iterationStep < maxSteps; iterationStep++ ) {
      System.out.println("Sequential iteration " + iterationStep);
      
      /*
       * Generate all the alphas
       */
      // initialize the first alphas
      {
        double sum = 0.0;
        for ( int i = 0; i < N; i++ ) {
          double value = pi[i] * b[i][observedSequence[0]];
          alphas[0 * N + i] = value;
          sum += value;
        }
        
        // rescale
        double scale = 1.0 / sum;
        alphasScales[0] = scale;
        
        for ( int i = 0; i < N; i++) {
          alphas[0 * N + i] *= scale; 
        }
      }
      
      // compute the other alphas
      for ( int t = 1; t < sequenceLength; t++ ) {
        double sum = 0.0;
        for (int i = 0; i < N; i++) {
          double res = 0.0;
          // only compute necessary terms
          if ( i == 0 ) {
            for (int j = 0; j < N; j++) {
              res += (alphas[(t - 1) * N + j] * a[j][i]);
            }
          } else {
            res += (alphas[(t - 1) * N + 0] * a[0][i]);
            res += (alphas[(t - 1) * N + i] * a[i][i]);
          }
          double value = (res * b[i][observedSequence[t]]);
          alphas[t * N + i] = value;
          sum += value;
        }
        
        // rescale
        double scale = 1.0 / sum;
        alphasScales[t] = scale;

        for ( int i = 0; i < N; i++) {
          alphas[t * N + i] *= scale; 
        }
      }
      
      /*
       * Generate all the betas coefficients
       */
      for (int stateIndex = 0; stateIndex < N; stateIndex++) {
        betas[(sequenceLength - 1) * N + stateIndex] = 1.0d;
      }

      for (int t = sequenceLength - 1; t >= 1; t--) {
        for (int i = 0; i < N; i++) {
          double res = 0.0;
          // only compute the necessary terms
          if ( i == 0 ) {
            for (int j = 0; j < N; j++) {
              res += (betas[t * N + j] * a[i][j]
                     * b[j][observedSequence[t]] * alphasScales[t - 1]);
            }
          } else {
            res += (betas[t * N + 0] * a[i][0]
                    * b[0][observedSequence[t]] * alphasScales[t - 1]);
            res += (betas[t * N + i] * a[i][i]
                    * b[i][observedSequence[t]] * alphasScales[t - 1]);
          }
          betas[(t - 1) * N + i] = res;
        }
      }
      
      // reset temporary variables
      for ( int stateIndex = 0; stateIndex < N; stateIndex++ ) {
        Arrays.fill(aaStar[stateIndex], 0.0);
      }
      
      { /* compute and normalize Pi_star */
        double sum = 0.0;
        for (int i = 0; i < N; i++) {
          double gamma = alphas[0 * N + i] * betas[0 * N + i];
          double tempVal = gamma / alphasScales[0];
          piStar[i] = tempVal;
          sum += tempVal;
        }
        // renormalize
        for ( int i = 0; i < N; i++ ) {
          piStar[i] /= sum;
        }
      }
      
      // as we don't need to update b, we can stop at
      // sequenceLength-1
      for ( int t = 0; t < sequenceLength - 2; t++ ) {
        /* compute khi(i,j) incrementally, put it in aaStar */
        for (int i = 0; i < N; i++) {
          // only compute necessary terms
          if ( i == 0 ) {
            for (int j = 0; j < N; j++) {
              double khi = (alphas[t * N + i] * a[i][j] * betas[(t + 1) * N + j])
                      * b[j][observedSequence[t + 1]];
              aaStar[i][j] += khi;
            }
          } else {
            double khi0 = (alphas[t * N + i] * a[i][0] * betas[(t + 1) * N + 0])
                    * b[0][observedSequence[t + 1]];
            aaStar[i][0] += khi0;
            
            double khii = (alphas[t * N + i] * a[i][i] * betas[(t + 1) * N + i])
                    * b[i][observedSequence[t + 1]];
            aaStar[i][i] += khii;
          }
        }
      }
      
      // Renormalize aaStar
      for (int i = 0; i < N; i++) {
        double sum = 0.0;
        for (int j = 0; j < N; j++) {
          sum += aaStar[i][j];
        }
        if ( sum > 0.0 ) {
          for (int j = 0; j < N; j++) {
            aaStar[i][j] /= sum;
          }
        }
      }
      
      // check convergence
      double piDiff = 0.0;
      for ( int i = 0; i < N; i++ ) {
        piDiff += Math.abs( pi[i] - piStar[i] );
      }
      
      double aaDiff = 0.0;
      for (int i = 0; i < N; i++ ) {
        for (int j = 0; j < N; j++ ) {
          aaDiff += Math.abs( aaStar[i][j] - a[i][j] );
        }
      }
      
      // Copy back piStar and aaStar
      double[] temp1 = pi;
      pi = piStar;
      piStar = temp1;
      
      double[][] temp2 = a;
      a = aaStar;
      aaStar = temp2;
      
      // break when both criterion have been  met
      if ( piDiff < piThreshold && aaDiff < aaThreshold ) {
        break;
      }
    }
  }
  
  /**
   * Perform training on a spark Rdd observation sequence.
   * @param sc Spark context to use
   * @param observedSequenceRdd Rdd containing the sequence (seq index, word index)
   * @param piThreshold Threshold on pi
   * @param aaThreshold  Threshold on a
   * @param maxIterations Max number of iterations
   */
  public void rawSparkTrain(
          JavaSparkContext sc,
          JavaRDD<Tuple2<Integer, Integer>> observedSequenceRdd,
          double piThreshold,
          double aaThreshold,
          int maxIterations) {
    
    final int T = (int) observedSequenceRdd.count();    
    final int blockSize = Parameters.BWBlockSize;
    
    final int numBlocks = (T + (blockSize - 1)) / blockSize;
    double piDiff = Double.POSITIVE_INFINITY;
    double aaDiff = Double.POSITIVE_INFINITY;
    /*
    System.out.println("N " + N);
    System.out.println("a length" + a.length);
    System.out.println("a[0] length" + a[0].length);
    System.out.println("b length" + b.length);
    System.out.println("T " + T);
    System.out.println("numBlocks " + numBlocks);
    */
    JavaRDD<Tuple3<Integer, Integer, Integer>> observedSequenceWithBlockIdRdd =
            observedSequenceRdd.flatMap( new FlatMapFunction<Tuple2<Integer, Integer>, Tuple3<Integer, Integer, Integer>>() {
              private static final long serialVersionUID = 8L;

              @Override
              public Iterable<Tuple3<Integer, Integer, Integer>> call(Tuple2<Integer, Integer> arg0)
                      throws Exception {
                List<Tuple3<Integer, Integer, Integer>> returnList =
                        new ArrayList<Tuple3<Integer, Integer, Integer>>(2);
                
                int blockId = arg0._1 / blockSize;
                returnList.add( new Tuple3<Integer, Integer, Integer>(blockId, arg0._1, arg0._2));
                
                if ( blockId > 0 && arg0._1 % blockSize == 0 ) {
                  returnList.add( new Tuple3<Integer, Integer, Integer>(blockId - 1, arg0._1, arg0._2));
                }
                return returnList;
              }
              
            });
    
    
    JavaPairRDD<Integer, Iterable<Tuple3<Integer, Integer, Integer>>> groupedObservedBlocksRdd =
            observedSequenceWithBlockIdRdd.groupBy( new Function<Tuple3<Integer, Integer, Integer>, Integer>() {

              @Override
              public Integer call(Tuple3<Integer, Integer, Integer> arg0) throws Exception {
                return arg0._1();
              }
              
            }).repartition(numBlocks);
        
    JavaPairRDD<Integer, int[]> observedBlocksRdd =
            groupedObservedBlocksRdd.mapValues(new Function<Iterable<Tuple3<Integer, Integer, Integer>>, int[]>() {
              private static final long serialVersionUID = 7L;

              @Override
              public int[] call(Iterable<Tuple3<Integer, Integer, Integer>> arg0) throws Exception {
                int blockId = arg0.iterator().next()._1();
                int blockStart = blockId * blockSize;
                int blockEnd = Math.min( (blockId + 1) * blockSize, T);
                int trueBlockSize = (blockEnd - blockStart);
                
                int realBlockSize = 0;
                for ( Tuple3<Integer, Integer, Integer> tuple : arg0 ) {
                  realBlockSize++;
                }
                
                int[] observedBlock;
                if ( blockId == (numBlocks - 1) ) {
                  observedBlock = new int[blockEnd - blockStart];
                  if ( realBlockSize != (blockEnd - blockStart) )
                  {
                    System.out.println("Block sizes don't match: real " + realBlockSize
                            + " assumed " + trueBlockSize);
                  }
                } else {
                  observedBlock = new int[blockSize + 1];
                  if ( realBlockSize != blockSize + 1 )
                  {
                    System.out.println("Block sizes don't match: real " + realBlockSize
                            + " assumed " + (blockSize + 1));
                  }
                }
                
                for ( Tuple3<Integer, Integer, Integer> tuple : arg0 ) {
                  observedBlock[ tuple._2() - blockStart] = tuple._3();
                }
                
                //System.out.println("Block " + blockId + " seq " + Arrays.toString(observedBlock));
                
                return observedBlock;
              }
            });

   //observedBlocksRdd = observedBlocksRdd.persist(StorageLevel.MEMORY_AND_DISK());

    // iterate until convergence
    for ( int step = 0; step < maxIterations; step++ ) {
      System.out.println("Spark iter " + step);
      
      JavaPairRDD<Integer, SquareMatrix[]> initializedTaRdd =
              observedBlocksRdd.mapToPair( new PairFunction<Tuple2<Integer, int[]>, Integer, SquareMatrix[]>() {
                private static final long serialVersionUID = 2L;

                @Override
                public Tuple2<Integer, SquareMatrix[]> call(Tuple2<Integer, int[]> arg0)
                        throws Exception {
                  int blockId = arg0._1;
                  int blockStart = blockId * blockSize;
                  int blockEnd = Math.min( (blockId + 1) * blockSize, T);
                  int trueBlockSize = (blockEnd - blockStart);
                  
                  int[] observedBlock = arg0._2;
                  
                  SquareMatrix[] ta = new SquareMatrix[trueBlockSize];
                  
                  for ( int bi = 0; bi < trueBlockSize; bi++ ) {
                    int t = blockStart + bi;
                    
                    ta[bi] = new SquareMatrix(N);
                    
                    if ( t == 0 ) {
                      for ( int i = 0; i < N; i++ ) {
                        ta[bi].elements[i * N + i] = pi[i] *
                                b[i][observedBlock[0]];
                      }
                      
                    } else {
                      for (int i = 0; i < N; i++) {
                        for (int j = 0; j < N; j++) {
                          ta[bi].elements[i * N + j] = 
                                  a[j][i] * 
                                  b[i][observedBlock[bi]];
                        }
                      }
                    }
                    
                    double norm = ta[bi].rawNorm1();
                    
                    if ( norm <= 0.0 ) {
                      System.out.println("Block " + blockId + " matr " + bi 
                              + " norm " + norm + " mat " + ta[bi]);
                    }
                    ta[bi].scalarDivide(norm);
                    
                    // also perform partial scan
                    if ( bi > 0 ) {
                      SquareMatrix partialResult = new SquareMatrix(N);
                      double partialResultNorm = 0.0;
                      
                      for ( int i = 0; i < N; i++ ) {
                        if ( i == 0 ) {
                          for ( int j = 0; j < N; j++ ) {
                            double el = 0.0;
                            for (int k = 0; k < N; k++ ) {
                              el += ta[bi].elements[i * N + k]
                                      * ta[bi - 1].elements[ k * N + j];
                            }
                            partialResultNorm += el;
                            partialResult.elements[ i * N + j] = el;
                          }
                        } else {
                          // here, only k=0 and k=i only
                          for ( int j = 0; j < N; j++ ) {
                            double el = 0.0;
                            el += ta[bi].elements[i * N + 0]
                                    * ta[bi - 1].elements[ 0 * N + j];
                            el += ta[bi].elements[i * N + i]
                                    * ta[bi - 1].elements[ i * N + j];
                            partialResultNorm += el;
                            partialResult.elements[ i * N + j] = el;
                          }
                        }
                      }
                      
                      // renormalize
                      for ( int i = 0; i < N; i++ ) {
                        for ( int j = 0; j < N; j++ ) {
                          partialResult.elements[ i * N + j ] /= partialResultNorm;
                        }
                      }
                      
                      ta[bi] = partialResult;
                    }
                  }
                  
                  return new Tuple2<Integer, SquareMatrix[]>(blockId, ta);
                }
                
              });
      
      // make it persist in memory
      JavaPairRDD<Integer, SquareMatrix[]> partiallyScannedTaRdd = initializedTaRdd;
      
      JavaPairRDD<Integer, SquareMatrix> lastPartiallyScannedTaRdd =
              partiallyScannedTaRdd.mapValues(new Function<SquareMatrix[], SquareMatrix>() {

                @Override
                public SquareMatrix call(SquareMatrix[] arg0) throws Exception {
                  return arg0[ arg0.length - 1].publicClone();
                }
                
              });
      
      List<Tuple2<Integer, SquareMatrix>> lastPartiallyScannedTa =
              lastPartiallyScannedTaRdd.collect();
      
      // sort last partially scanned TA matrices
      Collections.sort(lastPartiallyScannedTa, new Comparator<Tuple2<Integer, SquareMatrix>>() {
        @Override
        public int compare(Tuple2<Integer, SquareMatrix> index1,
                Tuple2<Integer, SquareMatrix> index2) {
          return index1._1.compareTo(index2._1);
        }
      });
      
      
      List<SquareMatrix> fullyScannedTa = new ArrayList<SquareMatrix>(numBlocks);
      fullyScannedTa.add(lastPartiallyScannedTa.get(0)._2);
      
      // reduce on the master the last TAs
      for ( int i = 1; i < numBlocks; i++ ) {
        SquareMatrix left = fullyScannedTa.get(i - 1);
        SquareMatrix right = lastPartiallyScannedTa.get(i)._2;
        
        SquareMatrix out = right.multiplyOut(left, new SquareMatrix(N));
        
        double norm = out.rawNorm1();
        
        if ( norm <= 0.0 )
        {
          System.out.println("Norm of fully scanned TA " + i + " = " + norm);
        }
        out.scalarDivide(norm);
        fullyScannedTa.add(out);
      }
      
      class FinalTaMapper implements PairFunction<Tuple2<Integer, SquareMatrix[]>, Integer, SquareMatrix[]>{
        private static final long serialVersionUID = 5L;
        
        List<SquareMatrix> fullyScannedLastTa;
        
        public FinalTaMapper(List<SquareMatrix> fullyScannedLastTa ) {
          this.fullyScannedLastTa = fullyScannedLastTa;
        }
        
        @Override
        public Tuple2<Integer, SquareMatrix[]> call(Tuple2<Integer, SquareMatrix[]> arg0) throws Exception {
          int blockId = arg0._1;
          SquareMatrix[] inTAs = arg0._2;
          
          SquareMatrix[] outResults = new SquareMatrix[inTAs.length];
          
          if ( blockId > 0 ) {
            SquareMatrix prevBlockMatrix = fullyScannedLastTa.get(blockId - 1);
            
            for ( int bi = 0; bi < inTAs.length; bi++ ) {
              outResults[bi] = inTAs[bi].multiplyOut(prevBlockMatrix, new SquareMatrix(N));
              
              double norm = outResults[bi].rawNorm1();
              if ( norm <= 0.0 ) {
                System.out.println("(FinalTAMapper) Block " + blockId + " mat "
                        + bi + " norm " + norm);
              }
              
              outResults[bi].scalarDivide(norm);
            }
          } else {
            for ( int bi = 0; bi < inTAs.length; bi++ ) {
              outResults[bi] = inTAs[bi].publicClone();
            }
          }
          return new Tuple2<Integer, SquareMatrix[]>(blockId, outResults);
        }
        
      }
      
      JavaPairRDD<Integer, SquareMatrix[]> finalTaRdd =
              partiallyScannedTaRdd.mapToPair( new FinalTaMapper(fullyScannedTa));
      
      JavaPairRDD<Integer, double[]> alphaHatsRdd = finalTaRdd.mapValues(new Function<SquareMatrix[], double[]>(){
        private static final long serialVersionUID = 3L;

        @Override
        public double[] call(SquareMatrix[] arg0) throws Exception {
          int trueBlockSize = arg0.length;
          double[] alphas = new double[trueBlockSize * N];
          
          for ( int bi = 0; bi < trueBlockSize; bi++ ) {
            SquareMatrix curMatrix = arg0[bi];
            double totSum = 0.0;
            for (int i = 0; i < N; i++ ) {
              double sum = 0.0;
              for ( int j = 0; j < N; j++ ) {
                sum += curMatrix.elements[ i * N + j ];
              }
              alphas[ bi * N + i ] = sum;
              totSum += sum;
            }
            
            if ( totSum <= 0.0 ) {
              System.out.println("Alpha " + bi + " is null");
            }
          }
          
          return alphas;
        }
        
      });
      //alphaHatsRdd = alphaHatsRdd.persist(StorageLevel.MEMORY_ONLY());
      JavaPairRDD<Integer, double[]> lastAlphaHatsRdd = alphaHatsRdd.mapValues( new Function<double[], double[]>(){
        private static final long serialVersionUID = 4L;

        @Override
        public double[] call(double[] arg0) throws Exception {
          int trueBlockSize = arg0.length / N;
          double[] lastAlpha = new double[N];
          
          for ( int i = 0; i < N; i++ ) {
            lastAlpha[i] = arg0[ (trueBlockSize - 1) * N + i];
          }
          return lastAlpha;
        }
        
      });
      
      List<Tuple2<Integer, double[]>> lastAlphaHats = lastAlphaHatsRdd.collect();
      // sort last alpha hats
      Collections.sort(lastAlphaHats, new Comparator<Tuple2<Integer, double[]>>() {
        @Override
        public int compare(Tuple2<Integer, double[]> index1,
                Tuple2<Integer, double[]> index2) {
          return index1._1.compareTo(index2._1);
        }
      });
      
      JavaPairRDD<Integer, Tuple2<int[], double[]>> observationsWithAlphaHats
              = observedBlocksRdd.join(alphaHatsRdd);
      //observationsWithAlphaHats.persist(StorageLevel.MEMORY_ONLY());
      //alphaHatsRdd.unpersist();
      class CtMapper implements PairFunction<Tuple2<Integer, Tuple2<int[], double[]>>, Integer, double[]>{
        private static final long serialVersionUID = 9L;
        
        List<Tuple2<Integer, double[]>> lastAlphaHats;
        
        public CtMapper(List<Tuple2<Integer, double[]>> lastAlphaHats ) {
          this.lastAlphaHats = lastAlphaHats;
        }
        
        @Override
        public Tuple2<Integer, double[]> call(Tuple2<Integer, Tuple2<int[], double[]>> arg0) throws Exception {
          int blockId = arg0._1;
          int blockStart = blockId * blockSize;
          int blockEnd = Math.min( (blockId + 1) * blockSize, T);
          int trueBlockSize = (blockEnd - blockStart);
          
          Tuple2<int[], double[]> tuple = arg0._2;
          
          int[] observedBlock = tuple._1;
          double[] alphaHats = tuple._2;
          
          double[] cts = new double[trueBlockSize];
          
          { // bi = 0
            double den = 0.0;
            if ( blockStart == 0 ) {
              for (int i = 0; i < N; i++) {
                den += pi[i] * b[i][observedBlock[0]];
              }
            } else {
              double[] prevAlpha = lastAlphaHats.get(blockId - 1)._2;
              for ( int i = 0; i < N; i++ ) {
                double sum = 0.0;
                for ( int j = 0; j < N; j++ ) {
                  sum += a[j][i] * b[i][observedBlock[0]] * prevAlpha[j];
                }
                den += sum;
              }
            }
            
            if ( den <= 0.0 ) {
              System.out.println("den[0]=0.0");
            }
            cts[0] = 1.0 / den;
          }
          
          { // bi > 0
            for ( int bi = 1; bi < trueBlockSize; bi++ ) {
              double den = 0.0;
              for ( int i = 0; i < N; i++ ) {
                double sum = 0.0;
                for ( int j = 0; j < N; j++ ) {
                  sum += a[j][i] * b[i][observedBlock[bi]] * alphaHats[(bi - 1) * N + j];
                }
                den += sum;
              }
              
              if ( den <= 0.0 ) {
                System.out.println("den[" + bi + "]=0.0");
              }
              cts[bi] = 1.0 / den;
            }
          }
          return new Tuple2<Integer, double[]>(blockId, cts);
        }
        
      }
      
      JavaPairRDD<Integer, double[]> ctsRdd =
              observationsWithAlphaHats.mapToPair( new CtMapper(lastAlphaHats));
    
      JavaPairRDD<Integer, Tuple2<int[], double[]>> observedBlocksWithCtsRdd =
              observedBlocksRdd.join(ctsRdd);
      
      JavaPairRDD<Integer, SquareMatrix[]> initializedTbRdd =
              observedBlocksWithCtsRdd.mapToPair( new PairFunction<Tuple2<Integer, Tuple2<int[],double[]>>, Integer,SquareMatrix[]>(){
                private static final long serialVersionUID = 10L;

                @Override
                public Tuple2<Integer, SquareMatrix[]> call(
                        Tuple2<Integer, Tuple2<int[], double[]>> arg0) throws Exception {
                  int blockId = arg0._1;
                  int blockStart = blockId * blockSize;
                  int blockEnd = Math.min( (blockId + 1) * blockSize, T);
                  int trueBlockSize = (blockEnd - blockStart);
                  
                  Tuple2<int[], double[]> tuple = arg0._2;
                  int[] observedBlock = tuple._1;
                  double[] cts = tuple._2;
                  
                  SquareMatrix[] tb = new SquareMatrix[trueBlockSize];
                  
                  for ( int bi = trueBlockSize - 1; bi >= 0; bi-- ) {
                    int t = blockStart + bi;
                    
                    tb[bi] = new SquareMatrix(N);
                    if ( t == T - 1 ) {
                      for ( int i = 0; i < N; i++ ) {
                        tb[bi].elements[ i * N + i ] = cts[bi];
                      }
                    } else {
                      for ( int i = 0; i < N; i++ ) {
                        for ( int j = 0; j < N; j++ ) {
                          tb[bi].elements[ i * N + j ] = a[i][j]
                                  * b[j][observedBlock[bi + 1]] * cts[bi];
                        }
                      }
                    }
                    
                    double norm = tb[bi].rawNorm1();
                    if ( norm <= 0.0 ) {
                      System.out.println("(TBInit) Block " + blockId + " mat "
                              + bi + " norm " + norm);
                    }
                    
                    // also perform partial scan
                    if ( bi < trueBlockSize - 1 ) {
                      SquareMatrix partialResult = new SquareMatrix(N);
                      for ( int i = 0; i < N; i++ ) {
                        if ( i == 0 ) {
                          for ( int j = 0; j < N; j++ ) {
                            double el = 0.0;
                            for (int k = 0; k < N; k++ ) {
                              el += tb[bi].elements[i * N + k]
                                      * tb[bi + 1].elements[ k * N + j];
                            }
                            partialResult.elements[ i * N + j] = el;
                          }
                        } else {
                          // here, only k=0 and k=i only
                          for ( int j = 0; j < N; j++ ) {
                            double el = 0.0;
                            el += tb[bi].elements[i * N + 0]
                                    * tb[bi + 1].elements[ 0 * N + j];
                            el += tb[bi].elements[i * N + i]
                                    * tb[bi + 1].elements[ i * N + j];
                            partialResult.elements[ i * N + j] = el;
                          }
                        }
                      }
                      
                      tb[bi] = partialResult;
                    }
                  }
                  return new Tuple2<Integer, SquareMatrix[]>(blockId, tb);
                }
              });
      
      JavaPairRDD<Integer, SquareMatrix[]> partiallyScannedTbRdd = initializedTbRdd;
      
      JavaPairRDD<Integer, SquareMatrix> firstPartiallyScannedTbRdd =
              partiallyScannedTbRdd.mapValues(new Function<SquareMatrix[], SquareMatrix>() {
                private static final long serialVersionUID = 13L;

                @Override
                public SquareMatrix call(SquareMatrix[] arg0) throws Exception {
                  return arg0[0].publicClone();
                }
                
              });
      
      List<Tuple2<Integer, SquareMatrix>> firstPartiallyScannedTb =
              firstPartiallyScannedTbRdd.collect();
      
      // sort first partially scanned TB matrices
      Collections.sort(firstPartiallyScannedTb, new Comparator<Tuple2<Integer, SquareMatrix>>() {
        @Override
        public int compare(Tuple2<Integer, SquareMatrix> index1,
                Tuple2<Integer, SquareMatrix> index2) {
          return index1._1.compareTo(index2._1);
        }
      });
            
      List<SquareMatrix> fullyScannedTb = new ArrayList<SquareMatrix>(numBlocks);
      fullyScannedTb.add(firstPartiallyScannedTb.get(numBlocks - 1)._2);
      
      // reduce on the master the first TBs
      for ( int i = numBlocks - 2; i >= 0; i-- ) {
        SquareMatrix left = firstPartiallyScannedTb.get(i)._2;
        SquareMatrix right = fullyScannedTb.get(0);
        
        SquareMatrix out = left.multiplyOut(right, new SquareMatrix(N));
        
        fullyScannedTb.add(0, out);
      }
      
      class FinalTbMapper implements PairFunction<Tuple2<Integer, SquareMatrix[]>, Integer, SquareMatrix[]>{
        private static final long serialVersionUID = 12L;
        
        List<SquareMatrix> fullyScannedFirstTb;
        
        public FinalTbMapper(List<SquareMatrix> fullyScannedFirstTb ) {
          this.fullyScannedFirstTb = fullyScannedFirstTb;
        }
        
        @Override
        public Tuple2<Integer, SquareMatrix[]> call(Tuple2<Integer, SquareMatrix[]> arg0) throws Exception {
          int blockId = arg0._1;
          SquareMatrix[] inTBs = arg0._2;
          
          SquareMatrix[] outResults = new SquareMatrix[inTBs.length];
          
          if ( blockId < numBlocks - 1 ) {
            SquareMatrix nextBlockMatrix = fullyScannedFirstTb.get(blockId + 1);
            
            for ( int bi = 0; bi < inTBs.length; bi++ ) {
              outResults[bi] = inTBs[bi].multiplyOut(nextBlockMatrix, new SquareMatrix(N));
              
            }
          } else {
            for ( int bi = 0; bi < inTBs.length; bi++ ) {
              outResults[bi] = inTBs[bi].publicClone();
            }
          }
          return new Tuple2<Integer, SquareMatrix[]>(blockId, outResults);
        }
        
      }
      
      JavaPairRDD<Integer, SquareMatrix[]> finalTbRdd =
              partiallyScannedTbRdd.mapToPair( new FinalTbMapper(fullyScannedTb));
      
      JavaPairRDD<Integer, double[]> betaHatsRdd =finalTbRdd.mapValues(new Function<SquareMatrix[], double[]>(){
        private static final long serialVersionUID = 20L;

        @Override
        public double[] call(SquareMatrix[] arg0) throws Exception {
          int trueBlockSize = arg0.length;
          double[] betas = new double[trueBlockSize * N];
          
          for ( int bi = 0; bi < trueBlockSize; bi++ ) {
            SquareMatrix curMatrix = arg0[bi];
            for (int i = 0; i < N; i++ ) {
              double sum = 0.0;
              for ( int j = 0; j < N; j++ ) {
                sum += curMatrix.elements[ i * N + j ];
              }
              betas[ bi * N + i ] = sum;
            }
            
          }
          
          return betas;
        }
        
      });
            
      
      
      JavaPairRDD<Integer, double[]> firstBetaHatsRdd = betaHatsRdd.mapValues(new Function<double[], double[]>(){

        @Override
        public double[] call(double[] arg0) throws Exception {
          double[] firstBeta = new double[N];
          
          for ( int i = 0; i < N; i++ ) {
            firstBeta[i] = arg0[i];
          }
          return firstBeta;
        }
        
      });
      
      List<Tuple2<Integer, double[]>> firstBetaHats = firstBetaHatsRdd.collect();
      // sort first beta hats
      Collections.sort(firstBetaHats, new Comparator<Tuple2<Integer, double[]>>() {
        @Override
        public int compare(Tuple2<Integer, double[]> index1,
                Tuple2<Integer, double[]> index2) {
          return index1._1.compareTo(index2._1);
        }
      });
      
      JavaPairRDD<Integer, Tuple2<Tuple2<int[], double[]>, double[]>> observationsWithAlphaHatsWithBetaHats =
              observationsWithAlphaHats.join(betaHatsRdd);
      
      class BlockSumKhiMapper implements PairFunction<Tuple2<Integer, Tuple2<Tuple2<int[], double[]>, double[]>>, Integer, double[]> {
        private static final long serialVersionUID = 21L;
        
        List<Tuple2<Integer, double[]>> firstBetaHats;
        
        public BlockSumKhiMapper( List<Tuple2<Integer, double[]>> firstBetaHats ) {
          this.firstBetaHats = firstBetaHats;
        }
        
        @Override
        public Tuple2<Integer, double[]> call(
                Tuple2<Integer, Tuple2<Tuple2<int[], double[]>, double[]>> arg0)
                throws Exception {
          int blockId = arg0._1;
          int blockStart = blockId * blockSize;
          int blockEnd = Math.min( (blockId + 1) * blockSize, T);
          int trueBlockSize = (blockEnd - blockStart);
          
          Tuple2<Tuple2<int[], double[]>, double[]> tuple0 = arg0._2;
          Tuple2<int[], double[]> tuple1 = tuple0._1;
          
          int[] observedBlock = tuple1._1;
          double[] alphas = tuple1._2;
          double[] betas = tuple0._2;
          double[] khis = new double[N * N];

          // process all but the last element of the block
          for ( int bi = 0; bi < trueBlockSize - 1; bi++ ) {
            for ( int i = 0; i < N; i++ ) {
              for ( int j = 0; j < N; j++ ) {
                double val = ((alphas[bi * N + i ] * a[i][j] )* (betas[ (bi + 1) * N + j ] * b[j][observedBlock[bi + 1]]));
                khis[ i * N + j ] += val;
              }
            }
          }
          
          // last element must get beta from firstBetaHats
          // we must not append the last value from the last block
          if ( blockId < numBlocks - 1 ) {
            double[] nextBeta = firstBetaHats.get( blockId + 1 )._2;
            int bi = trueBlockSize - 1;
            for ( int i = 0; i < N; i++ ) {
              for ( int j = 0; j < N; j++ ) {
                khis[ i * N + j ] += (alphas[bi * N + i ] * a[i][j] * nextBeta[j] * b[j][observedBlock[bi + 1]]);
              }
            }
          }
          
          return new Tuple2<Integer, double[]>( blockId, khis );
        } 
      }
      
      JavaPairRDD<Integer, double[]> blockKhisRdd =
              observationsWithAlphaHatsWithBetaHats.mapToPair(new BlockSumKhiMapper(firstBetaHats) );
      //observationsWithAlphaHats.unpersist();
      List<Tuple2<Integer, double[]>> blockKhis = blockKhisRdd.collect();
     
      
      JavaRDD<double[]> piRdd = observationsWithAlphaHatsWithBetaHats.flatMap(new FlatMapFunction<Tuple2<Integer,Tuple2<Tuple2<int[],double[]>,double[]>>,double[]>(){
        private static final long serialVersionUID = 42L;

        @Override
        public Iterable<double[]> call(
                Tuple2<Integer, Tuple2<Tuple2<int[], double[]>, double[]>> arg0) throws Exception {
          int blockId = arg0._1;
          
          if ( blockId == 0 ) {
            Tuple2<Tuple2<int[], double[]>, double[]> tuple0 = arg0._2;
            Tuple2<int[], double[]> tuple1 = tuple0._1;
            
            double[] alphas = tuple1._2;
            double[] betas = tuple0._2;
            
            double[] pi = new double[N];
            
            double sum = 0.0;
            for ( int i = 0; i < N; i++ ) {
              double val = alphas[i] * betas[i];
              sum += val;
              pi[i] = val;
            }
            
            for (int i = 0; i < N; i++ ) {
              pi[i] /= sum;
            }
            
            List<double[]> piList = new ArrayList<double[]>(1);
            piList.add(pi);
            
            return piList;
          } else {
            return new ArrayList<double[]>();
          }
        }
        
      });
      
      List<double[]> piList = piRdd.collect();
            
      // temporary variables
      double[][] aaStar = new double[N][N];
      
      // copy new a, renormalize it
      for ( int i = 0; i < N; i++ ) {
        double jsum = 0.0;
        for ( int j = 0; j < N; j++ ) {
          for ( int bi = 0; bi < numBlocks; bi++ ) {
            double[] khis = blockKhis.get(bi)._2;
            aaStar[i][j] += khis[ i * N + j ];
            jsum += khis[ i * N + j ];
          }
        }
        
        for ( int j = 0; j < N; j++ ) {
          aaStar[i][j] /= jsum;
        }
      }
      
      // copy piStar
      double[] piStar = piList.get(0);
      
      // check convergence
      piDiff = 0.0;
      for ( int i = 0; i < N; i++ ) {
        piDiff += Math.abs( pi[i] - piStar[i] );
      }
      
      aaDiff = 0.0;
      for (int i = 0; i < N; i++ ) {
        for (int j = 0; j < N; j++ ) {
          aaDiff += Math.abs( aaStar[i][j] - a[i][j] );
        }
      }
      
      // commit changes
      a = aaStar;
      pi = piStar;
      //break;
      //*
      if ( piDiff < piThreshold && aaDiff < aaThreshold ) {
        break;
      }
      //*/
    }
  }
  
  public int getN() {
    return N;
  }
  
  public int getM() {
    return M;
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
