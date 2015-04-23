package org.epfl.bigdataevs.em;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.math3.fraction.BigFraction;
import org.apache.commons.math3.fraction.Fraction;
import org.epfl.bigdataevs.eminput.ParsedArticle;
import org.epfl.bigdataevs.eminput.TimePartition;
import org.epfl.bigdataevs.eminput.TimePeriod;

import scala.Tuple2;

/**Team: Matias and Christian.
*EmInput: container for the RDDs representing the background
*model and the word distribution of every article, for all streams.
*Please note that the two JavaPairRDD attributes are not key-value maps,
*just lists of tuples.
*TODO: should the background model be per-stream?
**/

public class EmInput implements Serializable {
  /** Map containing tuples of words and their 
   * distribution in the streams. **/
  public Map<String, Double> backgroundModel;
  /** Collection containing articles published at that time. **/
  public Collection<Document> documents;
  
  /** List of the themes appearing in this input*/
  public ArrayList<Theme> themesOfPartition;
  
  /** Index of the EmInput in the RDD**/
  public Long indexOfPartition = 0L;
  
  /** Index of the run for this EmInput**/
  public int run;
  
  /** Time period containing all articles**/
  public TimePeriod timePeriod;
  
  public int numberOfIterations = 0;
  
  /**
   * EmInput contains at least the background model, 
   * the list of articles and the period delimiting these articles.
   * @param backgroundModel
   * @param documents
   * @param period
   */
  public EmInput(Map<String, Double> backgroundModel,
          Collection<Document> documents, TimePeriod period) {
    
    this.backgroundModel = backgroundModel;
    this.documents = documents;
    this.timePeriod = period;
    this.themesOfPartition = new ArrayList<>();
  }
  
  /**
   * EmInput builds with an instance of TimePartition
   * @param backgroundModel
   * @param Documents
   * @param period
   */ 
  public EmInput(TimePartition timePartition, Map<String, Fraction> backgroundModel) {
    this.timePeriod = timePartition.timePeriod;
    this.themesOfPartition = new ArrayList<>();
    this.backgroundModel = new HashMap<String, Double>();
    List<Document> convertedDocuments = new ArrayList<Document>();
    for (ParsedArticle article : timePartition.parsedArticles) {
      convertedDocuments.add(new Document(article));
    }
    this.documents = convertedDocuments;
    for (String word : backgroundModel.keySet()) {
      this.backgroundModel.put(word, backgroundModel.get(word).doubleValue());
    }
  }
  
  
  public void addTheme(Theme theme) {
    this.themesOfPartition.add(theme);
  }
  
  /**
   * Initialize all probabilities in the articles (article d belongs to theme j)
   */
  public void initializeArticlesProbabilities() {
    for (Document article : this.documents) {
      article.initializeProbabilities(themesOfPartition);
    }
  }
  
  /**
   * Compute the log-likelihood of the mixture model
   * @return log-likelohood
   */
  public double computeLogLikelihood(double lambdaBackgroundModel) {
    double logLikelihood = 0.0;
    for (Document article : documents) {
      for (String word : article.words.keySet()) {
        double temp = 0.0;
        for (Theme theme : themesOfPartition) {
          temp = temp + (article.probabilitiesDocumentBelongsToThemes.get(theme)
                  * theme.wordsProbability.get(word));
        }
        logLikelihood += article.words.get(word) * Math.log(
                (lambdaBackgroundModel * backgroundModel.get(word))
                + ((1.0 - lambdaBackgroundModel) * temp));
      }
    }
    return logLikelihood;
  }
  
  /**
   * Update probabilities word belongs to theme
   */
  public Double subUpdateProbabilitiesOfWordsGivenTheme(String word, Theme theme) {
    double value  = 0.0;
    for (Document article : this.documents) {
      if (article.words.containsKey(word)) {
        value = value + (((double) article.words.get(word))
                * (1.0 - article.probabilitiesHiddenVariablesBackgroundModel.get(word)) 
                        * (article.probabilitiesHiddenVariablesThemes.get(
                                Pair.of(word, theme))));
      }
    }
    return value;
  }
  
  /**
   * Update the probabilities that words belongs to themes
   * Do the computation for every themes
   */
  public void updateProbabilitiesOfWordsGivenTheme(ArrayList<Theme> themes) {
   
    for (Theme theme : themes) {
      double denominator = 0.0;
      for (String word : theme.wordsProbability.keySet()) {
        denominator = denominator + subUpdateProbabilitiesOfWordsGivenTheme(word, theme);
      }
      for (String word : theme.wordsProbability.keySet()) {
        double numerator = subUpdateProbabilitiesOfWordsGivenTheme(word, theme);
        theme.wordsProbability.put(word, numerator / (denominator + EmAlgo.epsilon));
      }
    }
    
  }
  
  /**
   * Clone the EmInput by replacing every article.
   */
  public EmInput clone() {
    Collection<Document> articles = new ArrayList<>();
    for (Document article : this.documents) {
      articles.add(new Document(article.words, article.stream));
    }
    return new EmInput(this.backgroundModel, articles, this.timePeriod);
  }
}
