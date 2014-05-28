(ns pink.event
  (:require 
    [pink.audio.engine :refer :all]
    [pink.audio.protocols :refer :all]
    [pink.audio.util :refer [create-buffer]] ))

(deftype Event [event-func ^Double start event-args ]
  Object
  (toString [e]  (format "\t%s\t%s\t%s\n" event-func start event-args )) 
  )

(defn event [f start & args]
  (Event. f start args)
  )


(defn events [f & args]
  (map #(apply event f %) args))


(defn event-list
  "Creates an EventList. 

  EventList's its own internal time and fires off events whose start times have
  been met.  Event have no notion of duration. An event may do things like 
  schedule an audio function to be added to an engine's performance list, force
  turning off an audio function, and so on."

  ([] (event-list []))
  ([evts] 
   {:events (ref (sort-by #(.start ^Event %) evts))       
    :cur-buffer (atom 0)
    }))

(defn event-list-add [evtlst ^Event evt]
  "Add an event to an event list"
  (when (< (.indexOf @(:events evtlst) evt) 0)
    (dosync
      (alter (:events evtlst) 
             (fn [a] (sort-by #(.start ^Event %) (conj a evt))))))
  evtlst)

(defn event-list-remove [evtlst evt]
  "remove an event from the event list"
  (do
    (dosync
      (alter (:events evtlst) (fn [a] (remove #(= % evt) a)))) 
    evtlst))

(defn- get-events! [evtlst cur-time]
  "alters an event list, returns events that are scheduled to be fired, updates 
  events in the event list"
  (dosync 
    (let [events (:events evtlst)
          [ready pending] (split-with #(<=  (.start ^Event %) cur-time) @events)]
      (ref-set events (if pending pending [])) 
      ready)))

(defn- fire-event [evt]
  (apply (.event-func ^Event evt) 
                 (.event-args ^Event evt)))

(defn event-list-tick [evtlst] 
  (let [cur-buffer (:cur-buffer evtlst)
        cur-time (/ (* @cur-buffer *ksmps*) *sr*)
        ready-events (get-events! evtlst cur-time)]
    (loop [[a & b] ready-events]
      (when a
        (do 
          (fire-event a)
          (recur b))))
    (swap! cur-buffer inc)))

;; Events functions dealing with audio engines

(defn fire-engine-event [arglst] 
  "create an instance of an audio function and adds to the engine" 
  (let [[eng f & args] arglst]
    (engine-add-afunc eng (apply f args))))

(defn wrap-engine-event [eng ^Event evt]
  (event fire-engine-event 
         (.start evt)
          (cons eng (cons (.event-func evt) (.event-args evt)))))

(defn engine-events [eng & args]
  (event-list (map #(wrap-engine-event eng %) args)))


(defn eng-events-runner [evtlst]
  (let [buf (create-buffer)]
    (fn ^doubles []
      (event-list-tick evtlst)
      (if (empty? @(:events evtlst))
        nil
        buf))))

(comment

  (defn test-event-list [evtlst]
    (let [wait (* 1000 (/ *ksmps* *sr*))]
      (loop []
        (event-list-tick evtlst)

        (when (> (count @(:events evtlst)) 0)
          (Thread/sleep 1) 
          (recur)
          ))))


  (defn test-func [a b] (println "test-func fired" a b))

  ;(events test-func [1 2 3] [4 5 6])

  (def test-note (event test-func 0.0 1.0 440.0))
  (def test-note-dupe (event test-func 0.0 1.0 440.0))
  (def test-note2 (event test-func 0.0 1.0 220.0))
  (def test-note3 (event test-func 2.0 1.5 110.0))
  (print (.start test-note3))

  (def evtlst (event-list [test-note]))
  (event-list-add evtlst test-note3)

  (print evtlst)

  (.start (Thread. ^Runnable (partial test-event-list evtlst))) 



  (def eng (engine-create))

  (def eng-events 
    (engine-events eng
                   (event test-func 0.0 1.5 110.0) 
                   (event test-func 0.0 1.5 120.0) 
                   (event test-func 1.0 1.5 130.0) 
                   (event test-func 2.0 1.5 140.0)))
  (print eng-events)
  (print (count @(:events eng-events)))
  (.start (Thread. ^Runnable (partial test-event-list eng-events))) 

  (print eng)

  ;(event-list-remove evtlst test-note)
  ;(event-list-tick evtlst)


  )