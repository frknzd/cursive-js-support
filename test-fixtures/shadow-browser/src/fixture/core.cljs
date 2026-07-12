(ns fixture.core)

(defn calculate [value]
  (let [doubled (* value 2)]
    (+ doubled 1)))

(defn trigger! []
  (let [answer (calculate 20)]
    (set! (.-textContent (.getElementById js/document "output"))
          (str "Answer: " answer))
    (throw (js/Error. "Mapped fixture exception"))))

(defn init []
  (.addEventListener (.getElementById js/document "trigger") "click" trigger!))
