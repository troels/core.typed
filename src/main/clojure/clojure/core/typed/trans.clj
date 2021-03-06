(in-ns 'clojure.core.typed)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dotted pre-type expansion

;tdr from Practical Variable-Arity Polymorphism paper
; Expand out dotted pretypes to fixed domain, using types bm, if (:name bound) = b
(defmulti trans-dots (fn [t b bm]
                       {:pre [(AnyType? t)
                              (symbol? b)
                              (every? Type? bm)]}
                       (class t)))

(defmethod trans-dots Name [t b bm] t)
(defmethod trans-dots F [t b bm] t)
(defmethod trans-dots Value [t b bm] t)
(defmethod trans-dots RClass [t b bm] t)

(defmethod trans-dots TApp
  [^TApp t b bm]
  (let [tfn #(trans-dots % b bm)]
    (->TApp (tfn (.rator t)) (mapv tfn (.rands t)))))

(defmethod trans-dots Union
  [t b bm]
  (let [tfn #(trans-dots % b bm)]
    (apply Un (doall (map tfn (:types t))))))

(defmethod trans-dots FnIntersection
  [t b bm]
  (let [tfn #(trans-dots % b bm)]
    (->FnIntersection (doall (map tfn (:types t))))))

(defmethod trans-dots Intersection
  [t b bm]
  (let [tfn #(trans-dots % b bm)]
    (apply In (doall (map tfn (:types t))))))

(defmethod trans-dots Function
  [t b bm]
  ;TODO how to handle filters?
;  (assert (NoFilter? (-> t :rng :fl)))
;  (assert (NoObject? (-> t :rng :o)))
  (let [tfn #(trans-dots % b bm)]
    (cond
      (:drest t)
      (let [{:keys [pre-type name]} (:drest t)]
        (assert (symbol? name))
        (if (= b name) ;identical bounds
          (let [dom (concat 
                        ;keep fixed domain
                        (doall (map tfn (:dom t)))
                        ;expand dotted type to fixed domain
                        (doall (map (fn [bk]
                                      {:post [(Type? %)]}
                                      ;replace free occurences of bound with bk
                                      (-> (substitute bk b pre-type)
                                        tfn))
                                    bm)))]
            (->Function dom
                        (update-in (:rng t) [:t] tfn)
                        nil
                        nil ;dotted pretype now expanded to fixed domain
                        nil))
          (-> t
            (update-in [:dom] #(doall (map tfn %)))
            (update-in [:rng :t] tfn)
            (update-in [:drest] (fn [drest]
                                  (when drest
                                    (-> drest
                                      (update-in [:pre-type] tfn)))))))) ;translate pre-type
      :else
      (-> t
        (update-in [:dom] #(doall (map tfn %)))
        (update-in [:rng] tfn)
        (update-in [:rest] #(when %
                              (tfn %)))))))

