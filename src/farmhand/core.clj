(ns farmhand.core
  (:require [clojure.core.async :as async]
            [farmhand.config :as config]
            [farmhand.jobs :as jobs]
            [farmhand.queue :as queue]
            [farmhand.redis :as redis :refer [with-jedis]]
            [farmhand.registry :as registry]
            [farmhand.work :as work])
  (:import (java.util.concurrent Executors TimeUnit))
  (:gen-class))

(defonce
  ^{:doc "An atom that contains the Jedis pool of the most recent invocation of
         start-server.

         For many applications, this atom will do the trick when you want to
         spin up a single server in your application and have easy access to
         the pool."}
  pool*
  (atom nil))

(defonce
  ^{:doc "An atom that contains the most recent server that was spun up. You
         can use this to easily stop a server without needing to store the
         server instance yourself."}
  server*
  (atom nil))

(defn enqueue
  "Pushes a job onto the queue. Returns the job's ID.

  The second argument is a Farmhand pool. If a pool is not given, the value in
  the pool* atom will be used."
  ([job]
   (enqueue job @pool*))
  ([job pool]
   (with-jedis pool jedis
     (let [job (jobs/normalize job)
           transaction (.multi jedis)]
       (jobs/save-new transaction job)
       (queue/push transaction job)
       (.exec transaction)
       (:job-id job)))))

(defn start-server
  ([] (start-server {}))
  ([{:keys [num-workers queues redis pool]}]
   (let [pool (or pool (redis/create-pool (config/redis redis)))
         shutdown-chan (async/chan)

         num-workers (config/num-workers num-workers)
         thread-pool (Executors/newFixedThreadPool num-workers)
         run-worker #(work/main-loop pool shutdown-chan (config/queues queues))
         _ (doall (repeatedly num-workers
                              #(.submit thread-pool ^Runnable run-worker)))


         cleanup-thread (async/thread (registry/cleanup-loop pool shutdown-chan))

         server {:pool pool
                 :shutdown-chan shutdown-chan
                 :thread-pool thread-pool
                 :cleanup-thread cleanup-thread}]
     (dosync
       (reset! pool* pool)
       (reset! server* server))
     server)))

(defn stop-server
  "Stops a running Farmhand server. If no server is given, this function will
  stop the server in the server* atom.

  By default this waits up to 2 minutes for the running jobs to complete. This
  value can be overriden with the :timeout-ms option."
  ([] (stop-server @server*))
  ([{:keys [pool shutdown-chan thread-pool cleanup-thread]}
    & {:keys [timeout-ms] :or {timeout-ms (* 1000 60 2)}}]
   (do
     (async/close! shutdown-chan)
     (.shutdown thread-pool)
     (.awaitTermination thread-pool timeout-ms TimeUnit/MILLISECONDS)
     (redis/close-pool pool))))

(defn -main
  [& _]
  (start-server))



(comment

  (do
    (start-server)
    (defn slow-job [& args] (Thread/sleep 10000) :slow-result)
    (defn failing-job [& args] (throw (ex-info "foo" {:a :b}))))

  (enqueue {:fn-var #'slow-job :args ["i am slow"]} @pool*)
  (enqueue {:fn-var #'failing-job :args ["fail"]} @pool*)

  (stop-server)
  )
