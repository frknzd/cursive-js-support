(ns fixture.core)

(defn calculate [value]
  (let [doubled (* value 2)]
    (+ doubled 1)))

(defn -main []
  (let [answer (calculate 20)]
    (js/console.log "Answer" answer)
    (throw (js/Error. "Mapped Node fixture exception"))))

(set! *main-cli-fn* -main)
