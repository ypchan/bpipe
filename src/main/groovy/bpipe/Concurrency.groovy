/*
 * Copyright (c) 2012 MCRI, authors
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package bpipe

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import groovy.util.logging.Log;


/**
 * A resource that can be managed by Bpipe.
 * The key is the name of the resource (eg: "memory") 
 * and the amount is the amount that is being used by  particular
 * operation.
 * <p>
 * Note: see {@link PipelineBodyCategory} for the magic that makes
 * these become referencable within pipline stages as "GB", 'threads',
 * and so on.
 * 
 * @author ssadedin
 */
@Log
class ResourceUnit implements Serializable {
    
    public static final long serialVersionUID = 0L
    
    int amount = 0;
    
    String key
    
    String toString() {
        "$amount $key"
    }
}

@Singleton
@Log
/**
 * Manages concurrency for parallel pipelines.
 * <p>
 * This class is responsible for managing a thread pool that is configured
 * with size according to the maximum concurrency specified by the user
 * on the command line (-n option), and it also handles execution of groups
 * of tasks as a unit so that they can all be entered into the common thread
 * pool and the flow returns only when they all are completed.
 * <p>
 * There are two layers of concurrency management implemented. The first is the
 * raw capacity of the thread pool. This ensures that absolute concurrency within
 * Bpipe can't exceed the user's configuration.  However there is a second, logical
 * level of concurrency that is enforced on top of that, using a global semaphore
 * that is acquired/released as each parallel segment runs. The purpose of this
 * second "logical" level is that it allows a user to reserve more than n=1 concurrency
 * for a single thread if that thread will create particularly heavy load. The 
 * obvious situation where that happens is if the thread itself launches child threads
 * that are outside of Bpipe's control, or if it runs (shell) commands that themeselves
 * launch multiple threads. In these cases the "logical" concurrency control can 
 * be used to restrict the actual concurrency below that enforced by the physical
 * thread pool to manage the actual load generated by the pipeline.
 */
class Concurrency {
    
    /**
     * The thread pools to use for executing tasks. Pools are organised into tiers,
     * where dependent threads *must* be placed in different tiers, to ensure there
     * cannot be a possibility of deadlock.
     */
    List<ThreadPoolExecutor> pools = Collections.synchronizedList([initPool()])
    
    /**
     * Each resource allocation allocates resources for its resource type against
     * these resource allocations.
     */
    Map<String,Semaphore> resourceAllocations = initResourceAllocations()    
	
    /**
     * Counts of threads running
     */
    Map<Runnable,AtomicInteger> counts = [:]
    
    ThreadPoolExecutor initPool(int numThreads=-1) {
        
        if(numThreads < 0)
            numThreads = Config.config.maxThreads*2 
        
        log.info "Creating thread pool with " + numThreads + " threads to execute parallel pipelines"
        
        ThreadFactory threadFactory = { Runnable r ->
                          def t = new Thread(r)  
                          t.setDaemon(true)
                          return t
                        } as ThreadFactory
        
        // LinkedBlockingQueue vs SynchronousBlockingQueue vs ArrayBlockingQueue?
        //
        //    - SynchronousBlockingQueue holds zero elements. That means if no thread in 
        //      the pool, it creates a new one, corePoolSize is IGNORED
        //            
        //    - LinkedBlockingQueue : a queue with infinite capacity, means that if
        //      no thread available will wait until one available. maxPoolSize ignored,
        //      only uses corePoolSize
        //     
        //    - ArrayBlockingQueue : a queue with fixed size. Will throw error when
        //     number of items exceeds capcacity
        //
        // In practise, observe that SynchronousQueue allows unlimited simultaneous
        // threads, it essentially disables the queueing and makes it so that any 
        // overflow from the pool results in a new thread being created.
        // 
        return new ThreadPoolExecutor(numThreads, Integer.MAX_VALUE,
                                      0L, TimeUnit.MILLISECONDS,
                                      new LinkedBlockingQueue<Runnable>(), 
//                                      new SynchronousQueue<Runnable>(), 
//                                      new ArrayBlockingQueue<Runnable>(Config.config.maxThreads), 
                                      threadFactory) {
              @Override
              void afterExecute(Runnable r, Throwable t) {
                  AtomicInteger runningCount
                  synchronized(counts) {
                    runningCount = counts[r]
                  }
                  
                  int value = runningCount.decrementAndGet()
                  
                  log.info "Decremented running count to $value in thread " + Thread.currentThread().name
                  
                  // Notify parent that will be waiting on this count
                  // for each decrement
                  synchronized(runningCount) {
                      runningCount.notify()
                  }
              }
        }
    }
        

    Map initResourceAllocations() {

        Map res = [ threads: new Semaphore(Config.config.maxThreads)]

        if(Config.userConfig.maxMemoryMB) {
            res["memory"] = new Semaphore(Integer.parseInt(Config.userConfig.maxMemoryMB))
        }               
        
        if(Config.config.maxMemoryMB) {
            log.info "Setting maximum memory to $Config.config.maxMemoryMB from configuration / command line"
            res["memory"] = new Semaphore(Config.config.maxMemoryMB)
        }               
		return res
    }
    
    /**
     * Execute the given list of runnables using the specified thread pool. Thread pools are maintained in
     * tiers, which are designed to separate threads which have dependencies (hence raising the
     * possibility of deadlocks). Each nested level of the pipeline runs in a separate "tier".
     * <p>
     * This method waits for all the runnables in the given list to finish before returning.
     * 
     * @param runnables
     */
    void execute(List<Runnable> runnables, int tier=0) {
        
        synchronized(this.pools) {
            while(this.pools.size()<=tier) {
                println "Creating thread pool for tier $tier"
                this.pools.add(initPool(Config.config.maxThreads+1))
            }
        }
        
        ThreadPoolExecutor pool = pools[tier]
        
        AtomicInteger runningCount = new AtomicInteger()
        
        // First set up the count of running pipelines
        for(Runnable r in runnables) {
            synchronized(counts) {
                runningCount.incrementAndGet()
                counts[r] = runningCount
            }
        }
        
        // Now put them in the thread pool
        for(Runnable r in runnables) {
            pool.execute(r); 
        }
            
        // Wait until the count of running threads reaches zero.
        // The count is decremented by the ThreadPoolExecutor#afterExecute
        // call as each thread finishes
        long lastLogTimeMillis = 0
        while(runningCount.get()) {
                
            if(lastLogTimeMillis < System.currentTimeMillis() - 5000) {
                log.info("Waiting for " + runningCount.get() + " parallel stages to complete (pool.active=${pool.activeCount} pool.tasks=${pool.taskCount})" )
                lastLogTimeMillis = System.currentTimeMillis()
            }
                    
            synchronized(runningCount) {
                runningCount.wait(50)
            }
                
            if(runningCount.get())
                Thread.sleep(300)
        }
    }

   /**
    * Called by parallel paths before they begin execution: enforces overall concurrency by blocking
    * the thread before it can start work. (ie. this method may block).
    */
   void acquire(ResourceUnit resourceUnit) {
        Semaphore resource
        synchronized(resourceAllocations) {
            resource = resourceAllocations.get(resourceUnit.key)
        }
        
        if(resource == null) {
            log.info "Unknown resource type $resourceUnit.key specified: treating as infinite resource"
            return
        }
        
       int amount = resourceUnit.amount
        
       log.info "Thread " + Thread.currentThread().getName() + " requesting for $amount concurrency permit(s) type $resourceUnit.key with " + resource.availablePermits() + " available"
       long startTimeMs = System.currentTimeMillis()
       resource.acquire(amount)
       long durationMs = startTimeMs - System.currentTimeMillis()
       if(durationMs > 1000) {
           log.info "Thread " + Thread.currentThread().getName() + " blocked for $durationMs ms waiting for resource $resourceUnit.key amount(s) $amount"
       }
       else
           log.info "Thread " + Thread.currentThread().getName() + " acquired resource $resourceUnit.key in amount $amount"
   }
   
   void release(ResourceUnit resourceUnit) {
        Semaphore resource
        synchronized(resourceAllocations) {
            resource = resourceAllocations.get(resourceUnit.key)
        }
        
        if(resource == null) {
            log.info "Unknown resource type $resourceUnit.key specified: treating as infinite resource"
            return
        }
        
       resource.release(resourceUnit.amount)
       log.info "Thread " + Thread.currentThread().getName() + " releasing $resourceUnit.amount $resourceUnit.key"
   }
   
   void setLimit(String resourceName, int amount) {
       this.resourceAllocations.put(resourceName, new Semaphore(amount))
   }
   
   void initFromConfig() {
       
       if(!Config.userConfig.limits) 
           return
       
       Config.userConfig.limits.each { key, value ->
           log.info "Setting limit $key with value $value from user configuration"
           setLimit(key, value)
       }
   }
}
