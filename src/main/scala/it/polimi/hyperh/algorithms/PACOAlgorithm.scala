package it.polimi.hyperh.algorithms
import it.polimi.hyperh.problem.Problem
import it.polimi.hyperh.solution.EvaluatedSolution
import scala.util.Random
import it.polimi.hyperh.search.NeighbourhoodSearch
import util.Timeout
import util.ConsolePrinter
import it.polimi.hyperh.solution.Solution

/**
 * @author Nemanja
 */
class PACOAlgorithm(p: Problem, t0: Double, cand: Int, seedOption: Option[Solution]) extends MMMASAlgorithm(p, t0, cand, seedOption) {
  /**
   * A secondary constructor.
   */
  def this(p: Problem, seedOption: Option[Solution]) {
    this(p, 0.2, 5, seedOption)//default values
  }
  def this(p: Problem) {
    this(p, 0.2, 5, None)//default values
  }
  private var seed = seedOption
  
  def initialSolution(p: Problem): EvaluatedSolution = {
    seed match {
      case Some(seed) => seed.evaluate(p)
      case None => initNEHSolution(p)
    }
  }
  
  //remove Tmax and Tmin limits
  override def setT(iJob: Int, jPos: Int, newTij: Double) = {
    val i = iJob - 1
    val j = jPos - 1
    T(i)(j) = newTij
  }
  //differential initialization of trails based on the seed solution
  def initializeTrails(bestSolution: EvaluatedSolution) = {
    val seed = bestSolution.solution
    val Zbest = bestSolution.value
    for(i <- 1 to p.numOfJobs)
      for(j <- 1 to p.numOfJobs) {
        val iJobPos = seed.indexWhere( _ == i) + 1
        val diff = scala.math.abs(iJobPos - j) + 1
        if(diff <= p.numOfJobs/4.0) {
          setT(i, j, (1.0/Zbest))
        } 
        else if(p.numOfJobs/4.0 < diff && diff <= p.numOfJobs/2.0) {
          setT(i, j, (1.0/(2*Zbest)))
        } 
        else {
          setT(i, j, (1.0/(4*Zbest)))
        }
      }
  }
  override def initialSolution() = {
    var solution = initialSolution(p)
    solution = localSearch(solution, Timeout.setTimeout(300))
    updateTmax(solution)
    updateTmin
    initializeTrails(solution)
    solution
  }
  override def constructAntSolution(bestSolution: EvaluatedSolution): EvaluatedSolution = {  
    var scheduled: List[Int] = List()
    var jPos = 1
    var notScheduled = (1 to p.numOfJobs).toList//.filterNot(j => scheduled.contains(j))
    var candidates: List[Int] = List()
    
    while(jPos <= p.numOfJobs) {
      var nextJob = -1
      var u = Random.nextDouble()
      if(u <= 0.4) {
        nextJob = bestSolution.solution.toList.filterNot(job => scheduled.contains(job)).head
      }
      else if(u <= 0.8) {
        candidates = bestSolution.solution.toList.filterNot(job => scheduled.contains(job)).take(cand)
        var max = 0.0
        while(candidates.size != 0) {
          val sij = sumij(candidates.head, jPos)
          if(sij > max) {
            max = sij
            nextJob = candidates.head
          }
          candidates = candidates.tail
        }//end while
      }
      else {
        candidates = bestSolution.solution.toList.filterNot(job => scheduled.contains(job)).take(cand)
        nextJob = getNextJob(scheduled, candidates, jPos)
      }
      scheduled = scheduled ::: List(nextJob)
      jPos = jPos + 1
    }
    p.evaluatePartialSolution(scheduled)
  }
  override def updatePheromones(antSolution: EvaluatedSolution, bestSolution: EvaluatedSolution) = {
    val usedSolution = antSolution
    val Zcurrent = antSolution.value
    for(i <- 1 to p.numOfJobs)
      for(j <- 1 to p.numOfJobs) {
        val h = antSolution.solution.indexWhere( _ == i) + 1
        val hbest = bestSolution.solution.indexWhere( _ == i) + 1
        val x = scala.math.abs(hbest - j) + 1
        val diff = scala.math.pow(x, 1/2.0)
        var newTij: Double = -1.0
        
        if(p.numOfJobs <= 40) {
          if(scala.math.abs(h - j) <= 1){
            newTij = persistenceRate * trail(i,j) + (1.0/(diff*Zcurrent))
          } else {
            newTij = persistenceRate * trail(i,j)
          }
        }
        else {
          if(scala.math.abs(h - j) <= 2){
            newTij = persistenceRate * trail(i,j) + (1.0/(diff*Zcurrent))
          } else {
            newTij = persistenceRate * trail(i,j)
          }
        }
        setT(i, j, newTij)
      }
  }
  //job-index-based swap scheme
  def swapScheme(completeSolution: EvaluatedSolution, expireTimeMillis: Double): EvaluatedSolution = {
    var bestSolution = completeSolution
    val seed = bestSolution.solution
    val seedList = seed.toList
    var i = 1
    while(i <= p.numOfJobs && Timeout.notTimeout(expireTimeMillis)){
      var j = 1
      while(j <= p.numOfJobs && Timeout.notTimeout(expireTimeMillis)) {
        if(seed(j-1) != i) {
          val indI = seed.indexWhere( _ == i)
          val indK = j-1
          val neighbourSol = NeighbourhoodSearch.SWAPdefineMove(seedList, indI, indK)
          val evNeighbourSol = p.evaluatePartialSolution(neighbourSol)
          if(evNeighbourSol.value < bestSolution.value)
            bestSolution = evNeighbourSol
        }
        j = j + 1
      }//while j
      i = i + 1
    }//while i
    bestSolution
  }
  override def evaluate(p: Problem, timeLimit: Double): EvaluatedSolution = {
    val bestSol = new EvaluatedSolution(999999999, p.jobs)//dummy initialization
    val expireTimeMillis = Timeout.setTimeout(timeLimit)
    def loop(bestSol: EvaluatedSolution, iter: Int): EvaluatedSolution = {
      if(notStopCondition && Timeout.notTimeout(expireTimeMillis)) {
        var bestSolution = bestSol
        if(iter == 0) {
          bestSolution = initialize()
        }
        var antSolution = constructAntSolution(bestSolution)//pass global best or ant best solution
        antSolution = localSearch(antSolution, Timeout.setTimeout(300))
        if(antSolution.value < bestSolution.value)
          bestSolution = antSolution
        updatePheromones(antSolution, bestSolution)////use global best or ant best solution in impl
        if(iter % 40 == 0)
          bestSolution = swapScheme(bestSolution, expireTimeMillis)
        loop(bestSolution, iter + 1)
      }
      else bestSol
    }
    loop(bestSol, 0)
  }
  
  override def evaluate(p: Problem) = {
    val timeLimit = p.numOfMachines*(p.numOfJobs/2.0)*60//termination is n*(m/2)*60 milliseconds
    evaluate(p, timeLimit)
  }
  
  override def evaluate(p:Problem, seedSol: Option[Solution], timeLimit: Double):EvaluatedSolution = {
    seed = seedSol
    evaluate(p, timeLimit)
  }
}