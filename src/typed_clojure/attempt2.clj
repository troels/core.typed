(ns typed-clojure.attempt2
  (:import (clojure.lang Var Symbol IPersistentList IPersistentVector Keyword Cons))
  (:use [trammel.core :only [defconstrainedrecord defconstrainedvar
                             constrained-atom]]
        [analyze.core :only [ast]])
  (:require [analyze.core :as a]
            [analyze.util :as util]
            [clojure.set :as set]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Debug macros

(def debug-mode (atom true))
(def print-warnings (atom true))

(defmacro warn [& body]
  `(when @print-warnings
     (println ~@body)))

(defmacro debug [& body]
  `(when @debug-mode
     (println ~@body)))

(defmacro check-form [form]
  `(check (a/ast ~form)))

(defmacro synthesize-form [form]
  `(synthesize (a/ast ~form)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utils

(declare map->PrimitiveClass map->ClassType)

(defn resolve-or-primitive [sym]
  (case sym
    char Character/TYPE
    boolean Boolean/TYPE
    byte Byte/TYPE
    short Short/TYPE
    int Integer/TYPE
    long Long/TYPE
    float Float/TYPE
    double Double/TYPE
    void nil
    (if-let [res (resolve sym)]
      res
      (throw (Exception. (str sym " does not resolve to a type"))))))

;(+T resolve-class-symbol [Symbol -> Object])
(defn- resolve-class-symbol 
  [sym]
  (let [t (resolve-or-primitive sym)]
    (assert (or (nil? sym) (= 'void sym) (class? t)) (str sym " expected to resolve to a class, instead " t))
    (if (.isPrimitive ^Class t)
      (map->PrimitiveClass
        {:the-class t})
      (map->ClassType
        {:the-class t}))))

(declare map->Fun map->arity union Nil)

;(+T method->fun [clojure.reflect.Method -> ITypedClojureType])
(defn- method->Fun [method]
  (map->Fun
    {:arities [(map->arity 
                 {:dom (->> 
                         (map resolve-class-symbol (:parameter-types method))
                         (map #(union [Nil %]))) ; Java methods can return null
                  :rng (union [Nil
                               (resolve-class-symbol (:return-type method))])})]}))

(defn var-or-class->sym [var-or-class]
  {:pre [(or (var? var-or-class)
             (class? var-or-class))]}
  (cond
    (var? var-or-class) (symbol (str (.name (.ns var-or-class))) (str (.sym var-or-class)))
    :else (symbol (.getName var-or-class))))

(defmacro map-all-true? [& body]
  `(every? true? (map ~@body)))

(declare subtype? unparse-type)

(defn unp
  "Unparse a type and return string representation"
  [t]
  (with-out-str (-> t unparse-type pr)))

(defn assert-subtype [actual-type expected-type & msgs]
  (assert (subtype? actual-type expected-type)
          (apply str "Expected " (unp expected-type) ", found " (unp actual-type)
                 msgs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type contexts

(declare Type?)

(defn type-db-var-contract [m]
  (and (every? namespace (keys @m))
       (every? Type? (vals @m))))

(defn type-db-atom-contract [m]
  (and (every? namespace (keys m))
       (every? Type? (vals m))))

(defconstrainedvar 
  ^:dynamic *type-db* 
  (constrained-atom {}
                    "Map from qualified symbols to types"
                    [type-db-atom-contract])
  "Map from qualified symbols to types"
  [type-db-var-contract])

(defn local-type-db-contract [m]
  (and (every? (complement namespace) (keys m))
       (every? Type? (vals m))))

(defconstrainedvar 
  ^:dynamic *local-type-db* {}
  "Map from unqualified names to types"
  [local-type-db-contract])

(defn type-var-scope-contract [m]
  (and (every? (complement namespace) (keys m))
       (every? Type? (vals m))))

;(+T *type-var-scope* (IPersistentMap Symbol UnboundedTypeVariable))
(defconstrainedvar
  ^:dynamic *type-var-scope* {}
  "Map from unqualified names to types"
  [type-var-scope-contract])

(defn reset-type-db []
  (swap! *type-db* (constantly {})))

(defn type-of [sym-or-var]
  {:pre [(or (symbol? sym-or-var)
             (var? sym-or-var))]
   :post [(Type? %)]}
  (let [sym (if (var? sym-or-var)
              (symbol (str (.name (.ns sym-or-var))) (str (.sym sym-or-var)))
              sym-or-var)]
    (if-let [the-local-type (and (not (namespace sym))
                                 (*local-type-db* sym))]
      the-local-type
      (if-let [the-type (and (namespace sym)
                             (@*type-db* sym))]
        the-type
        (throw (Exception. (str "No type for " sym)))))))

(defmacro with-type-vars [var-map & body]
  `(binding [*type-var-scope* (merge *type-var-scope* ~var-map)]
     ~@body))

(defmacro with-local-types [type-map & body]
  `(binding [*local-type-db* (merge *local-type-db* ~type-map)]
     ~@body))

(defmacro with-type-anns [type-map-syn & body]
  `(binding [*type-db* (atom (apply hash-map (doall (mapcat #(list (or (when-let [var-or-class# (resolve (first %))]
                                                                         (var-or-class->sym var-or-class#))
                                                                       (when (namespace (first %))
                                                                         (first %))
                                                                       (symbol (str (ns-name *ns*)) (name (first %))))
                                                                   (parse-syntax (second %)))
                                                            '~type-map-syn))))]
     ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Typed Clojure Kinds

(def Type ::type-type)

(defn Type? [t]
  (isa? (class t) Type))

(defmacro def-type [nme & body]
  `(let [a# (defconstrainedrecord ~nme ~@body)]
     (derive a# Type)
     a#))

;; Single instance types

(def-type Value [val]
  "A singleton type for values, except nil"
  {:pre [(not (nil? val))]})

(declare subtype?)

(def-type Union [types]
  "A disjoint union of types"
  {:pre [(every? Type? types)
         (every? 
           (fn [t]
             (every? #(not (subtype? % t))
                     (disj (set types) t)))
           types)]})

(def-type NilType []
  "The nil value"
  [])

(def Nil (->NilType))

(def-type ClassType [the-class]
  "A class"
  {:pre [(class? the-class)]})

(def Any (Union. #{Nil (->ClassType Object)})) ; avoid constrained constructor because of
                                               ; call to subtype?, which is undefined
(def Any? (partial = Any))

(def Nothing (Union. []))
(def Nothing? (partial = Nothing))

(def True (->Value true))
(def True? (partial = True))

(def False (->Value false))
(def False? (partial = False))

(def falsy-values #{False Nil})

;; singleton types

(def-type ConstantVector [types]
  "A constant vector type"
  [(every? Type? types)])

(def-type ConstantList [types]
  "A constant list type"
  [(every? Type? types)])

;; Base types

(declare arity?)

(def-type Fun [arities]
  "Function with one or more arities"
  {:pre [(seq arities)
         (every? arity? arities)]})

(def-type PrimitiveClass [the-class]
  "A primitive class"
  {:pre [(or (nil? the-class) ; void primitive
             (and (class? the-class)
                  (.isPrimitive the-class)))]})

(def-type TProtocol [the-protocol]
  "A protocol"
  {:pre [(and (map? the-protocol)
              (:on the-protocol)
              (:var the-protocol))]})

(defn- simplify-union [the-union]
  (cond 
    (some #(instance? Union %) (:types the-union))
    (recur (->Union (set (doall (mapcat #(or (and (instance? Union %)
                                                  (:types %))
                                             [%])
                                        (:types the-union))))))

    (= 1
       (count (:types the-union)))
    (first (:types the-union))
    
    :else the-union))

(defn union [types]
  (simplify-union (->Union (set types))))

(def-type Intersection [types]
  "An intersection of types"
  {:pre [(every? Type? types)]})

;; type variables

(def-type UnboundedTypeVariable [nme]
  "A record for unbounded type variables, with an unqualified symbol as a name"
  {:pre [(symbol? nme)
         (not (namespace nme))]})

(def type-variables #{UnboundedTypeVariable})

(defn type-variable? [t]
  (boolean (type-variables (class t))))

;; arities

;(defprotocol IArity
;  (matches-args [this args] "Return the arity if it matches the number of args,
;                            otherwise nil")
;  (match-to-fun-arity [this fun-type] "Return an arity than appears to match a fun-type
;                                      arity, by counting arguments, not subtyping"))

(def Arity ::arity-type)

(defn Arity? [a]
  (isa? (class a) Arity))

(declare FilterSet?)

;; arity is NOT a type
(def-type arity [dom rng rest-type flter type-params]
  "An arity with fixed or variable domain. Supports optional filter, and optional type parameters"
  {:pre [(every? Type? dom)
         (Type? rng)
         (or (nil? rest-type)
             (Type? rest-type))
         (or (nil? flter)
             (FilterSet? flter))
         (or (nil? type-params)
             (every? type-variable? type-params))]})

(defn subtypes?*-varargs [argtys dom rst]
  (loop [dom dom
         argtys argtys]
    (cond
      (and (empty? argtys)
           (empty? dom))
      true

      (empty? argtys)
      false

      (and (empty? dom)
           rst)
      (if (subtype? (first argtys) rst)
        (recur dom (next argtys))
        false)

      (empty? dom)
      false

      (subtype? (first argtys)
                (first dom))
      (recur (next argtys)
             (next dom))

      :else false)))


(def top-arity ::top-arity)

(declare subtype?)

(defn subtype?*-arity [s t]
  (assert (not (:rest-type s)))
  (assert (not (:rest-type t)))
  (and (map-all-true? subtype? 
                      (:dom s)
                      (:dom t))
       (subtype? (:rng s)
                 (:rng t))))

(defn match-to-fun-arity [s fun-type]
  (first 
    (filter #(= (count (:dom s))
                (count (:dom %)))
            (:arities fun-type))))


(defn matches-args [arr args]
  (when (or (and (:rest-type arr)
                 (<= (count (:dom arr))
                     (count args)))
            (= (count (:dom arr))
               (count args)))
    arr))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Filters

(def ^:private Filter ::filter-type)

(defmacro def-filter [nme & body]
  `(let [a# (defconstrainedrecord ~nme ~@body)]
     (derive a# Filter)
     a#))

(defn Filter? [a]
  (isa? (class a) Filter))

(def-filter TrivialFilter []
  "A proposition that is always true"
  [])

(def-filter ImpossibleFilter []
  "A proposition that is never true"
  [])

(def-filter TypeFilter [var type]
  "A proposition that says var is of type type"
  {:pre [(symbol? var)
         (Type? type)]})

(def-filter NotTypeFilter [var type]
  "A proposition that says var is not of type type"
  {:pre [(symbol? var)
         (Type? type)]})

(def-filter FilterSet [then else]
  "Contains two propositions, then for the truthy result,
  else for the falsy result"
  {:pre [(Filter? then)
         (Filter? else)]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parse Type syntax

(defprotocol IParseType
  (parse-syntax* [this]))

(declare Fun-literal All-literal)

(defn parse-syntax
  "Type syntax parser, entry point"
  {:post [Type?]}
  [syn]
  (parse-syntax* (cond 
                   (and (list? syn)
                        (= (first syn)
                           All-literal)) (list Fun-literal syn) ; wrap (All [x] [x -> x]) sugar with (Fun ..)
                   (vector? syn) (list Fun-literal syn) ; wrap arity sugar [] with (Fun ..)
                   :else syn)))

(def parse parse-syntax)

(extend-protocol IParseType
  Symbol
  (parse-syntax* [this]
    (cond
      (*type-var-scope* this) (*type-var-scope* this) ; type variables
      (nil? this) Nil ;; nil
      :else (let [res (resolve-or-primitive this)]
              (cond
                (nil? res) (map->PrimitiveClass
                             {:the-class nil}) ;; void primtive

                (class? res) (if (.isPrimitive res) 
                               (map->PrimitiveClass
                                 {:the-class res})
                               (map->ClassType
                                 {:the-class res}))

                (Any? @res) Any
                (Nothing? @res) Nothing

                (var? res) (map->TProtocol
                             {:the-protocol @res})))))
  
  Boolean
  (parse-syntax* [this]
    (if this
      True
      False))

  String
  (parse-syntax* [this]
    (->Value this))

  Keyword
  (parse-syntax* [this]
    (->Value this))

  Double
  (parse-syntax* [this]
    (->Value this))
  
  Long
  (parse-syntax* [this]
    (->Value this))
  
  nil
  (parse-syntax* [this]
    Nil))

(defmulti parse-list-syntax first)

(def All-literal 'All)
(def U-literal 'U)
(def I-literal 'I)
(def Fun-literal 'Fun)
(def predicate-literal 'predicate)

(defmethod parse-list-syntax All-literal
  [[_ [& type-var-names] & [syn & more]]]
  (let [_ (assert (not more) "Only one arity allowed in All scope")
        type-vars (map #(map->UnboundedTypeVariable {:nme %})
                       type-var-names)

        type-var-scope (->> (mapcat vector type-var-names type-vars)
                         (apply hash-map))]

    (with-type-vars type-var-scope
      (assoc (parse-syntax* syn)
             :type-params type-vars))))
        

(defmethod parse-list-syntax U-literal
  [[_ & syn]]
  (union (doall (map parse-syntax syn))))

(defmethod parse-list-syntax I-literal
  [[_ & syn]]
  (map->Intersection 
    {:types (doall (map parse-syntax syn))}))

(defmethod parse-list-syntax predicate-literal
  [[_ & [typ-syntax :as args]]]
  (assert (= 1 (count args)))
  (let [pred-type (parse-syntax typ-syntax)]
    (->Fun [(map->arity
              {:dom [Any]
               :rng (->ClassType Boolean)
               :pred-type pred-type
               :named-params '(a)
               :flter (map->FilterSet
                        {:then (map->TypeFilter
                                 {:var 'a
                                  :type pred-type})
                         :else (map->NotTypeFilter
                                 {:var 'a
                                  :type pred-type})})})])))

(defmethod parse-list-syntax 'quote
  [[_ & [sym :as args]]]
  (assert (= 1 (count args)))
  (assert (symbol? sym))
  (->Value sym))

(defmethod parse-list-syntax Fun-literal
  [[_ & arities]]
  (map->Fun 
    {:arities (doall (map parse-syntax* arities))})) ; parse-syntax* to avoid implicit arity sugar wrapping

(extend-protocol IParseType
  IPersistentList
  (parse-syntax* [this]
    (parse-list-syntax this))

  Cons
  (parse-syntax* [this]
    (parse-list-syntax this)))

(defn- split-arity-syntax 
  "Splits arity syntax into [dom rng opts-map]"
  [arity-syntax]
  (assert (some #(= '-> %) arity-syntax) (str "Arity " arity-syntax " missing return type"))
  (let [[dom [_ rng & opts]] (split-with #(not= '-> %) arity-syntax)]
    [dom rng (apply hash-map opts)]))

(defn- parse-filter [syn]
  (assert (vector? syn))
  (let [[nme-sym keyw type-syn] syn
        type (parse-syntax type-syn)]
    (case keyw
      :-> (map->TypeFilter
            {:var nme-sym
             :type type})
      :!-> (map->NotTypeFilter
             {:var nme-sym
              :type type}))))

(extend-protocol IParseType
  IPersistentVector
  (parse-syntax* [this]
    (let [[dom rng opts-map] (split-arity-syntax this)

          [fixed-dom-maybe-named [_ uniform-rest-type :as rest-args]]
          (split-with #(not= '& %) dom)

          _ (assert (or (and (every? vector? fixed-dom-maybe-named)
                             (not (seq rest-args)))
                        (every? (complement vector?) fixed-dom-maybe-named))
                    "Either all or no parameters must be named, and cannot name a rest argument")

          named-params (when (every? vector? fixed-dom-maybe-named)
                         (map first fixed-dom-maybe-named))

          fixed-dom (if (every? vector? fixed-dom-maybe-named)
                      (map #(nth % 2) fixed-dom-maybe-named)
                      fixed-dom-maybe-named)

          _ (assert (or (not (seq rest-args))
                        (= 2 (count rest-args)))
                    "Incorrect uniform variable arity syntax")

          extras (into {}
                       (for [[nme syn] opts-map]
                         (cond
                           (= :filter nme) [:flter (map->FilterSet
                                                     {:then (parse-filter (:then syn))
                                                      :else (parse-filter (:else syn))})]

                           :else (throw (Exception. (str "Unsupported option " nme))))))

          fixed-dom-types (doall (map parse-syntax fixed-dom))
          rng-type (parse-syntax rng)
          uniform-rest-type (when uniform-rest-type
                              (parse-syntax uniform-rest-type))]
      (map->arity
        (merge
          {:dom fixed-dom-types
           :rng rng-type}
          (when uniform-rest-type
            {:rest-type uniform-rest-type})
          (when named-params
            {:named-params named-params})))))

  nil
  (parse-syntax* [_]
    Nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Unparse type syntax

(defprotocol IUnparseType
  (unparse-type* [this]))

(defn unparse-type
  [type-obj]
  (unparse-type* type-obj))

(def unparse unparse-type)

(defmulti unparse-filter class)

(defmethod unparse-filter FilterSet
  [{:keys [then else]}]
  {:then (unparse-filter then)
   :else (unparse-filter else)})

(defmethod unparse-filter TypeFilter
  [{:keys [var type]}]
  [var :-> (unparse-type type)])

(defmethod unparse-filter NotTypeFilter
  [{:keys [var type]}]
  [var :!-> (unparse-type type)])

(extend-protocol IUnparseType
  ClassType
  (unparse-type* [this]
    (symbol (.getName ^Class (:the-class this))))

  PrimitiveClass
  (unparse-type* [this]
    (cond
      (nil? (:the-class this)) 'void
      :else (symbol (.getName ^Class (:the-class this)))))

  Union
  (unparse-type* [this]
    (list* U-literal (doall (map unparse-type (:types this)))))

  Intersection
  (unparse-type* [this]
    (list* I-literal (doall (map unparse-type (:types this)))))

  Fun
  (unparse-type* [this]
    (list* Fun-literal (doall (map unparse-type (:arities this)))))

  arity
  (unparse-type* [this]
    (let [dom (doall (map unparse-type (:dom this)))
          ;; handle named parameters
          dom (if-let [names (seq (:named-params this))]
                (doall
                  (map #(vector %1 :- %2)
                       names
                       dom))
                dom)
          rng (unparse-type (:rng this))
          flter (when-let [flter (:flter this)]
                  (unparse-filter flter))

          sig (-> (concat dom 
                          (when (:rest-type this)
                            ['& (unparse-type (:rest-type this))])
                          ['-> rng]
                          (when flter
                            [:filter flter]))
                vec)]
      (if (seq (:type-params this))
        (list All-literal (vec (doall (map unparse-type (:type-params this))))
              sig)
        sig)))

  TProtocol
  (unparse-type* [this]
    (var-or-class->sym (-> this :the-protocol :var)))

  UnboundedTypeVariable
  (unparse-type* [{:keys [nme]}]
    nme))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subtyping

;; TODO Type variables

(declare supertype-of-all subtype-of-all supertype-of-one)

(defmulti subtype?* (fn [s t]
                      [(class s)
                       (class t)]))

;unions

(defmethod subtype?* [Union Union]
  [s t]
  (supertype-of-all t (:types s)))

(defmethod subtype?* [Type Union]
  [s t]
  (subtype-of-all s (:types t)))

(defmethod subtype?* [Union Type]
  [s t]
  (supertype-of-all t (:types s)))

;singletons

(defmethod subtype?* [Value Value]
  [s t]
  (= (:val s)
     (:val t)))

(defmethod subtype?* [Value ClassType]
  [s t]
  (subtype?* (->ClassType (-> s :val class))
             t))

;classes

(defmethod subtype?* [ClassType ClassType]
  [s t]
  (isa? (:the-class s)
        (:the-class t)))

;nil

(defmethod subtype?* [NilType NilType]
  [s t]
  true)

;function

(defmethod subtype?* [Fun Fun]
  [{s-arities :arities} {t-arities :arities}]
  (every? true?
          (map #(supertype-of-one % s-arities)
               t-arities)))

(defmethod subtype?* [arity arity]
  [{s-dom :dom 
    s-rng :rng
    s-rest :rest-type
    :as s}
   {t-dom :dom
    t-rng :rng
    t-rest :rest-type
    :as t}]
  (cond
    ;; simple case
    (and (not s-rest)
         (not t-rest))
    (and (subtypes? t-dom s-dom)
         (subtype? s-rng t-rng))

    (not s-rest)
    false

    (and s-rest
         (not t-rest))
    (and (subtypes?*-varargs t-dom s-dom s-rest)
         (subtype? s-rng t-rng))

    (and s-rest
         t-rest)
    (and (subtypes?*-varargs t-dom s-dom s-rest)
         (subtype? t-rest s-rest)
         (subtype? s-rng t-rng))

    :else false))

;default

(defmethod subtype?* [Type Type]
  [s t]
  false)

(defn supertype-of-one
  "True if t is a supertype to at least one ss"
  [t ss]
  (some #(subtype? % t) ss))

(defn subtype-of-all 
  "True if s is subtype of all ts"
  [s ts]
  (every? true?
          (map #(subtype? s %) ts)))

(defn supertype-of-all
  "True if t is a supertype of all ss"
  [t ss]
  (every? true?
          (map #(subtype? % t) ss)))

(defn subtypes? [ss ts]
  (and (= (count ss)
          (count ts))
       (every? true? 
               (map subtype? ss ts))))

(defn subtype? [s t]
  (subtype?* s t))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Variable Elimination

(defmulti replace-variables 
  "In type t, replace all occurrences of x with v"
  (fn [t x->v]
    (assert (every? type-variable? (keys x->v)) x->v)
    (assert (every? Type? (vals x->v)) x->v)
    (class t)))

(defn- arity-introduces-shadow? [t x]
  (some #(= % x) (:type-params t)))

(defn- unique-variable
  "Generate a globally unique type variable based on t"
  [t]
  (assert (type-variable? t))
  (-> t
    (update-in [:nme] #(-> % name gensym))))

(defn- rename-shadowing-variable 
  "Renames a type parameter provided by arity t, from variable x to v.
  Takes function f that takes 1 argument providing the replacement function"
  [t x v f]
  (f #(replace-variables % {x (unique-variable x)})))

(defn- handle-replace-arity 
  [t x->v rename-arity replace-free]
  ;; handle inner scopes, generate unique names for
  ;; variables with same name but different scope
  (loop [t t
         x->v x->v]
    (let [[x v] (first x->v)]
      (if (seq x->v)
        (recur
          (cond
            (arity-introduces-shadow? t x)
            (rename-arity t x v)

            :else
            (replace-free t x v))
          (next x->v))
        t))))

(defmethod replace-variables arity
  [t x->v]
  (letfn [(rename-fixed-arity-shadow [t x v]
            (rename-shadowing-variable t x v
                                       (fn [rplc]
                                         (-> t
                                           (update-in [:dom] #(doall (map rplc %)))
                                           (update-in [:rest-type] #(when % (rplc %)))
                                           (update-in [:rng] rplc) 
                                           (update-in [:type-params] #(doall (map rplc %)))))))
          
          (replace-fixed-arity-free-variable [t x v]
            (let [rplc #(replace-variables % {x v})]
              (-> t
                (update-in [:dom] #(doall (map rplc %)))
                (update-in [:rest-type] #(when % (rplc %)))
                (update-in [:rng] rplc))))]
    (handle-replace-arity
      t
      x->v
      rename-fixed-arity-shadow
      replace-fixed-arity-free-variable)))

(defmethod replace-variables Fun
  [t x->v]
  (let [rplc #(replace-variables % x->v)]
    (-> t
      (update-in [:arities] #(doall (map rplc %))))))

(defmethod replace-variables UnboundedTypeVariable
  [t x->v]
  (if-let [v (x->v t)]
    v
    t))

(defmethod replace-variables :default
  [t x->v]
  t)

;; Local Type Inference (2000) Pierce & Turner, Section 3.2

(defmulti promote 
  "Return the least supertype of s that does not reference any type variables
  in the set v"
  (fn [s v] (class s)))

(defmulti demote 
  "Return the greatest subtype of s that does not reference any type variables
  in the set v"
  (fn [s v] (class s)))

(defmethod promote Type
  [s v]
  (cond
    (Any? s) Any
    (Nothing? s) Nothing
    :else s))

(defmethod promote UnboundedTypeVariable
  [s v]
  (if (v s)
    Any
    s))

(defn- rename-type-args 
  "Rename any type parameters conflicting with type variables in set v"
  [s v]
  (assert (and (arity? s)
               (not (:rest-type s))))
  (let [renames (into {}
                      (map #(vector % (unique-variable %))
                           (filter v (:type-params s))))]
    (if (seq renames)
      ; rename shadowing variables
      (let [rplc #(replace-variables % renames)]
        (-> s
          (update-in [:dom] #(doall (map rplc %)))
          (update-in [:rng] rplc)
          (update-in [:rest-type] #(when % (rplc %)))
          (update-in [:type-params] #(doall (map rplc %)))))
      s)))

(defmethod promote arity
  [s v]
  (let [s (rename-type-args s v)
        dmt #(demote % v)
        pmt #(promote % v)]
    (-> s
      (update-in [:dom] #(doall (map dmt %)))
      (update-in [:rest-type] #(when % (dmt %)))
      (update-in [:rng] pmt))))

(defmethod promote Fun
  [s v]
  (let [pmt #(promote % v)]
    (-> s
      (update-in [:arities] #(doall (map pmt %))))))

(defmethod demote Type
  [s v]
  (cond
    (Any? s) Any
    (Nothing? s) Nothing
    :else s))

(defmethod demote UnboundedTypeVariable
  [s v]
  (if (v s)
    Nothing
    s))

(defmethod demote arity
  [s v]
  (let [s (rename-type-args s v)
        pmt #(promote % v)
        dmt #(demote % v)]
    (-> s
      (update-in [:dom] #(doall (map pmt %)))
      (update-in [:rest-type] #(when % (pmt %)))
      (update-in [:rng] dmt))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constraint generation

;; Local Type Inference (2000) Pierce & Turner, Section 3.3

(defconstrainedrecord TypeVariableConstraint [type-var upper-bound lower-bound]
  "A constraint on a type variable type-var. Records an upper and lower bound"
  {:pre [(type-variable? type-var)
         (Type? upper-bound)
         (Type? lower-bound)]})

(defconstrainedrecord ConstraintSet [constraints]
  "A constraint set, each constraint for a different variable"
  {:pre [(every? TypeVariableConstraint? constraints)
         (let [no-duplicates? (fn [{:keys [type-var]}]
                                (= 1
                                   (count 
                                     (filter #(= (:type-var %) type-var)
                                             constraints))))]
           (every? no-duplicates? constraints))]})

(defn intersect-constraint-sets
  "Returns the intersection of constraint sets cs"
  [& cs]
  (let [merged-constraints (for [[t constraints] (group-by :type-var (mapcat :constraints cs))]
                             (map->TypeVariableConstraint
                               {:type-var t
                                :lower-bound (union (map :lower-bound constraints))
                                :upper-bound (map->Intersection
                                               {:types (map :upper-bound constraints)})}))]
    (map->ConstraintSet
      {:constraints merged-constraints})))

(defn trivial-constraint 
  "Return the trivial constraint for variable x"
  [x]
  (map->TypeVariableConstraint
    {:type-var x
     :lower-bound Nothing
     :upper-bound Any}))

(defn empty-constraint-set
  "Returns the empty constraint set for variables in xs"
  [xs]
  (map->ConstraintSet
    {:constraints (map trivial-constraint xs)}))

(defn singleton-constraint-set
  "Returns the singleton constraint set, containing the provided
  constraint, with the trivial constraint for each variable in set xs"
  [constraint xs]
  (map->ConstraintSet
    {:constraints (cons constraint (map trivial-constraint xs))}))

(declare constraint-gen*)

(defn constraint-gen [s t xs v]
  (let [conflicts (set/intersection xs v)
        renames (into {}
                      (for [n conflicts]
                        [n (unique-variable n)]))

        ;; enforce (set/intersection xs v) => #{}
        [s t] (map #(replace-variables % renames) [s t])
        xs (set (replace renames xs))]
    (constraint-gen* s t xs v)))

(defmulti constraint-gen*
  "Given a set of type variables v, a set of unknowns xs, and
  two types s and t, calculate the minimal (ie. least contraining)
  xs/v constraint set C that guarantees s <: t"
  (fn [s t xs v]
    (assert (set? xs))
    [(class s) (class t)]))

(defn- eliminate-variables 
  "Eliminate all variables in set v that occur in type t
  by renaming them. Respects inner scopes, renaming accordingly"
  [t v]
  (let [subst (into {}
                    (for [tv v]
                      [tv (unique-variable tv)]))]
    (replace-variables t subst)))

(defn cg-upper [y s xs v]
  (let [s (eliminate-variables s xs)
        t (demote s v)]
    (singleton-constraint-set
      (map->TypeVariableConstraint
        {:type-var y
         :lower-bound Nothing
         :upper-bound t})
      (disj xs y))))

(defn cg-lower [s y xs v]
  (let [s (eliminate-variables s xs)
        t (promote s v)]
    (singleton-constraint-set
      (map->TypeVariableConstraint
        {:type-var y
         :lower-bound t
         :upper-bound Any})
      (disj xs y))))

(defmethod constraint-gen* [Type Type]
  [s t xs v]
  (cond
    (xs s) (cg-upper s t xs v)
    (xs t) (cg-lower s t xs v)
    ;; cg-refl
    :else (empty-constraint-set xs)))

(defmethod constraint-gen* [arity arity]
  [s t xs v]
  (let [;; enforce (set/intersection (:type-params s)
        ;;                           (set/union xs v))
        ;;         => #{}
        conflicts (set/intersection (set (:type-params s))
                                    (set/union xs v))

        renames (into {}
                      (for [v conflicts]
                        [v (unique-variable v)]))
        
        [s t] (map #(replace-variables % renames)
                   [s t])

        v-union-ys (set/union v (:type-params s))

        cs (map #(constraint-gen %1 %2 xs v-union-ys)
                 (concat (:dom t) (when (:rest-type t) [(:rest-type t)]))
                 (concat (:dom s) (when (:rest-type s) [(:rest-type s)])))
        
        d (constraint-gen (:rng s) (:rng t) xs v-union-ys)]
    (apply intersect-constraint-sets d cs)))

(defmethod constraint-gen* [Fun Fun]
  [s t xs v]
  (->>
    (for [s-arity (:arities s)]
      (constraint-gen
        s-arity
        (match-to-fun-arity s-arity t)
        xs 
        v))
    (apply intersect-constraint-sets)))

(defn minimal-substitution [r constraint-set]
  (into {}
        (loop
          [min-sub {}
           cs (:constraints constraint-set)]
          (if (empty? cs)
            min-sub
            (let [{x :type-var
                   s :lower-bound
                   t :upper-bound} (first cs)
                  sub-entry (cond
                              ;; r is constant or covariant in x
                              (or (subtype? (replace-variables r {x Nothing})
                                            (replace-variables r {x Any}))
                                  (subtype? (replace-variables r {x s})
                                            (replace-variables r {x t})))
                              [x s]

                              ;; r is contravariant in x
                              (subtype? (replace-variables r {x t})
                                        (replace-variables r {x s}))
                              [x t]

                              ;; r is invariant in x and (= s t)
                              (and (= s t)
                                   (subtype? (replace-variables r {x s})
                                             (replace-variables r {x t})))
                              [x s]

                              :else (throw (Exception. "No substitution exists to satisfy type")))]
              (recur 
                (into min-sub
                      [sub-entry])
                (next cs)))))))

  
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type Inference

;; Bidirectional checking (Local Type Inference (2000) Pierce & Turner, Section 4)

(declare tc-expr)

(defn tc-expr-check [expr expected-type]
  (let [typed-expr (tc-expr expr)]
    (assert-subtype (::+T expr) expected-type)
    typed-expr))

(defmulti tc-expr :op)

(defmethod tc-expr :number
  [{:keys [val] :as expr}]
  (assoc expr
         ::+T (->Value 1)))

;; var

;(defmethod check :var
;  [{:keys [var tag] :as expr}]
;  (let [expected-type (::+T expr)
;        actual-type (type-of var)]
;    (assert-subtype actual-type expected-type (str " for var " var))
;    (assoc expr
;           ::+T actual-type)))
;
;(defmethod synthesize :var
;  [{:keys [var tag] :as expr}]
;  (let [actual-type (type-of var)]
;    (assoc expr
;           ::+T actual-type)))
;
;;; def
;
;(defn infer-def [{:keys [var init init-provided] :as expr}]
;  (let [var-type (type-of var)
;        checked-init-expr (if init-provided
;                            (-> init
;                              (assoc ::+T var-type)
;                              check)
;                            (do
;                              (debug "No init provided for" var ", ignore body")
;                              init))]
;    (assoc expr
;           :init checked-init-expr
;           ::+T (map->TClass
;                  {:the-class Var}))))
;
;(defmethod check :def
;  [expr]
;  (let [expected-type (::+T expr)
;        _ (assert expected-type "def in checking mode requires full type annotation")
;        inferred-def (infer-def expr)
;        actual-type (::+T inferred-def)
;        _ (assert-subtype actual-type expected-type)]
;    inferred-def))
;
;(defmethod synthesize :def
;  [expr]
;  (infer-def expr))
;
;(defn- infer-invoke [{:keys [fexpr args] :as expr}]
;  (let [synthesized-fexpr (synthesize fexpr)
;        fexpr-type (::+T synthesized-fexpr)
;        arity-type (some #(matches-args % args) (:arities fexpr-type))
;
;        _ (assert arity-type)
;
;        expected-dom (take (count args)
;                           (concat (:dom arity-type)
;                                   (when (:rest-type arity-type)
;                                     (repeat (:rest-type arity-type)))))
;
;        checked-args (doall (map #(-> %1
;                                    (assoc ::+T %2)
;                                    check)
;                                 args
;                                 expected-dom))
;
;        ;; instatiate type arguments
;        constraint-set (let [cs (doall (map #(constraint-gen %1 %2 (set (:type-params arity-type)) #{})
;                                            (doall (map ::+T checked-args))
;                                            expected-dom))]
;                         (apply intersect-constraint-sets cs))
;        
;        min-sub (minimal-substitution arity-type constraint-set)
;        _ (println "before" (unp arity-type))
;        arity-type (let [rplc #(replace-variables % min-sub)]
;                     (-> arity-type
;                       (update-in [:dom] #(doall (map rplc %)))
;                       (update-in [:rest-type] #(when % (rplc %)))
;                       (update-in [:rng] rplc)))
;
;        _ (println "after" (unp arity-type))
;
;        instatiated-fexpr (assoc synthesized-fexpr
;                                 ::+T (map->Fun {:arities [arity-type]}))
;        return-type (:rng arity-type)]
;    (assoc expr
;           :fexpr instatiated-fexpr
;           :args checked-args
;           ::+T return-type)))
;
;;; invoke
;
;(defmethod synthesize :invoke
;  [expr]
;  (infer-invoke expr))
;
;(defmethod check :invoke
;  [expr]
;  (let [expected-type (::+T expr)
;        _ (assert expected-type "Checking context for function invocation requires full
;                                type annotations")
;        inferred-expr (infer-invoke expr)
;        actual-type (::+T inferred-expr)
;        _ (assert-subtype actual-type expected-type)]
;    inferred-expr))
;
;;; if
;
;(defmethod synthesize :if
;  [{:keys [test then else] :as expr}]
;  (let [[synthesized-test
;         synthesized-then
;         synthesized-else
;         :as typed-exprs]
;        (map synthesize [test then else])
;        
;        actual-type (union (map ::+T typed-exprs))]
;    (assoc expr
;           :test synthesized-test
;           :then synthesized-then
;           :else synthesized-else
;           ::+T actual-type)))
;
;(defmethod check :if
;  [{:keys [test then else] :as expr}]
;  (let [expected-type (::+T expr)
;        _ (assert expected-type "if in checking mode requires full annotation")
;
;        synthesized-test (synthesize test)
;
;        check-else? (boolean (falsy-values (::+T synthesized-test)))
;
;        checked-then (check (assoc then 
;                                   ::+T expected-type))
;
;        inferred-else (if check-else?
;                        (check (assoc else
;                                      ::+T expected-type))
;                        (do 
;                          (warn "Unreachable else clause")
;                          (synthesize else)))
;        
;        actual-type (union (map ::+T (concat [checked-then] (when check-else?
;                                                              inferred-else))))
;        _ (assert-subtype actual-type expected-type)]
;    (assoc expr 
;           :test synthesized-test
;           :then checked-then
;           :else inferred-else
;           ::+T actual-type)))
;
;;; local bindings
;
;(defmethod synthesize :local-binding-expr
;  [{:keys [local-binding] :as expr}]
;  (let [synthesized-lb (synthesize local-binding)
;        actual-type (::+T synthesized-lb)]
;    (assoc expr
;           :local-binding synthesized-lb
;           ::+T actual-type)))
;
;(defmethod check :local-binding-expr
;  [{:keys [local-binding] :as expr}]
;  (let [expected-type (::+T expr)
;        _ (assert expected-type (str "Local binding " (:sym local-binding)
;                                     " requires type annotation in checking context."))
;        checked-lb (check (assoc local-binding
;                                 ::+T expected-type))
;        actual-type (::+T checked-lb)
;        _ (assert-subtype actual-type expected-type)]
;    (assoc expr
;           :local-binding checked-lb
;           ::+T actual-type)))
;
;(defmethod check :local-binding
;  [{:keys [sym init] :as expr}]
;  (let [expected-type (::+T expr)
;        _ (assert expected-type sym)
;        actual-type (type-of sym)
;        _ (assert-subtype actual-type expected-type)]
;    (assoc expr
;           ::+T actual-type)))
;
;;; literals
;
;(defmulti constant-type class)
;
;(defmethod constant-type Boolean
;  [b]
;  (if (true? b)
;    True
;    False))
;
;(defmethod constant-type nil
;  [k]
;  Nil)
;
;(defmethod constant-type Keyword
;  [k]
;  (->KeywordType k))
;
;(defmethod constant-type Symbol
;  [s]
;  (->SymbolType s))
;
;(defmethod constant-type String
;  [s]
;  (->StringType s))
;
;(defmethod constant-type Long
;  [l]
;  (->LongType l))
;
;(defmethod constant-type Double
;  [d]
;  (->DoubleType d))
;
;(defmethod constant-type IPersistentVector
;  [v]
;  (->ConstantVector (map constant-type v)))
;
;(defmacro literal-dispatches [disp-keyword]
;  `(do
;     (defmethod synthesize ~disp-keyword
;       [expr#]
;       (let [val# (:val expr#)
;             actual-type# (constant-type val#)]
;         (assoc expr#
;                ::+T actual-type#)))
;
;     (defmethod check ~disp-keyword
;       [expr#]
;       (let [val# (:val expr#)
;             expected-type# (::+T expr#)
;             actual-type# (constant-type val#)]
;         (assert-subtype actual-type# expected-type#)
;         (assoc expr#
;                ::+T actual-type#)))))
;
;(doseq [k #{:keyword :string :symbol :constant :number :boolean :nil}]
;  (literal-dispatches k))
;
;;; empty-expr
;
;(defmethod check :empty-expr
;  [{:keys [coll] :as expr}]
;  (let [expected-type (::+T expr)
;        _ (assert expected-type "empty-expr: must provide expected type in checking mode")
;        actual-type (map->TClass {:the-class (class coll)})
;        _ (assert-subtype actual-type expected-type)]
;    (assoc expr
;           ::+T actual-type)))
;
;(defmethod synthesize :empty-expr
;  [{:keys [coll] :as expr}]
;  (let [actual-type (map->TClass {:the-class (class coll)})]
;    (assoc expr
;           ::+T actual-type)))
;
;;; let
;
;(defmethod synthesize :binding-init
;  [{:keys [sym init] :as expr}]
;  (let [synthesized-init (synthesize init)]
;    (assoc expr
;           :init synthesized-init
;           ::+T (::+T synthesized-init))))
;
;(defmethod check :let
;  [{:keys [binding-inits body is-loop] :as expr}]
;  (assert (not is-loop) "Loop not implemented")
;  (let [expected-type (::+T expr)
;        _ (assert expected-type)
;        
;        [typed-binding-inits local-types]
;        (loop [binding-inits binding-inits
;               typed-binding-inits []
;               local-types {}]
;          (if (empty? binding-inits)
;            [typed-binding-inits local-types]
;            (let [[bnd-init] binding-inits
;                  typed-bnd-init (with-local-types local-types
;                                   (synthesize bnd-init))
;                  local-type-entry [(-> typed-bnd-init :local-binding :sym)
;                                    (-> typed-bnd-init ::+T)]
;                  _ (assert (every? identity local-type-entry))]
;              (recur (rest binding-inits)
;                     (conj typed-binding-inits typed-bnd-init)
;                     (conj local-types local-type-entry)))))
;        
;        checked-body (with-local-types local-types
;                       (check (assoc body
;                                     ::+T expected-type)))]
;    (assoc expr
;           :binding-inits typed-binding-inits
;           :body checked-body
;           ::+T (-> checked-body ::+T))))
;
;;; fn
;
;(defmethod check :fn-expr
;  [{:keys [methods] :as expr}]
;  (let [expected-type (::+T expr)
;        _ (assert (instance? Fun expected-type) (str "Expected Fun type, instead found " (unparse-type expected-type)))
;
;        checked-methods (doall 
;                          (for [method methods]
;                            (let [_ (assert (not (:rest-param method)))
;                                  arity (some #(matches-args % (:required-params method)) (:arities expected-type))
;                                  _ (assert arity 
;                                            (str "No arity with " (count (:required-params method)) 
;                                                 " parameters in type "
;                                                 expected-type))]
;                              (check (assoc method
;                                            ::+T arity)))))
;
;        actual-type (map->Fun
;                      {:arities (doall (map ::+T checked-methods))})
;
;        _ (assert-subtype actual-type expected-type)]
;    (assoc expr
;           ::+T actual-type
;           :methods checked-methods)))
;
;(defmethod check :fn-method
;  [{:keys [required-params rest-param body] :as expr}]
;  (assert (not rest-param))
;  (let [expected-arity-type (::+T expr)
;        _ (assert (not (:rest-type expected-arity-type)))
;        
;        typed-required-params (doall 
;                                (map #(assoc %1 ::+T %2)
;                                     required-params
;                                     (:dom expected-arity-type)))
;
;        typed-lbndings (apply hash-map 
;                              (doall (mapcat #(vector (:sym %) 
;                                                      (::+T %))
;                                             typed-required-params)))
;
;        checked-body (with-local-types typed-lbndings
;                       (check (assoc body
;                                     ::+T (:rng expected-arity-type))))]
;    (assoc expr
;           :required-params typed-required-params
;           :body checked-body)))
;
;;; do
;
;(defmethod synthesize :do
;  [{:keys [exprs] :as expr}]
;  (let [synthesized-exprs (vec (doall (map synthesize exprs)))
;        actual-type (-> synthesized-exprs last ::+T)
;        _ (assert actual-type)]
;    (assoc expr
;           :exprs synthesized-exprs
;           ::+T actual-type)))
;
;(defmethod check :do
;  [{:keys [exprs] :as expr}]
;  (let [expected-type (::+T expr)
;        _ (assert expected-type "do requires type annotation in checking mode")
;
;        butlast-synthesized-exprs (vec (doall (map synthesize (butlast exprs))))
;        _ (assert (seq exprs))
;        last-checked-expr (check (assoc (last exprs)
;                                        ::+T expected-type))
;        typed-exprs (conj butlast-synthesized-exprs last-checked-expr)
;
;        actual-type (::+T last-checked-expr)
;        _ (assert actual-type last-checked-expr)]
;    (assoc expr
;           :exprs typed-exprs
;           ::+T actual-type)))
;
;;; static method
;
;(defn- overriden-annotation [{name-sym :name, class-sym :declaring-class,
;                              :keys [declaring-class parameter-types] :as method}]
;  (let [_ (assert (and class-sym name-sym)
;                  (str "Unresolvable static method " class-sym name-sym))
;        method-sym (symbol (name class-sym) (name name-sym))]
;    (try
;      (type-of method-sym)
;      (catch Exception e))))
;
;(defn infer-static-method [{:keys [method args] :as expr}]
;  (let [override (overriden-annotation method)
;        _ (if override
;            (println "Overriding static method " (symbol (name (:declaring-class method))
;                                                         (name (:name method))))
;            (println "Not overriding static method " (symbol (name (:declaring-class method))
;                                                             (name (:name method)))))
;        fun-type (if override
;                   override
;                   (method->Fun method))
;        arity-type (some #(matches-args % args) (:arities fun-type))
;
;        checked-args (doall 
;                       (map #(-> %1
;                               (assoc ::+T %2)
;                               check)
;                            args
;                            (:dom arity-type)))
;
;        actual-type (:rng arity-type)]
;    (assoc expr
;           :args checked-args
;           ::+T actual-type)))
;
;(defmethod synthesize :static-method
;  [expr]
;  (infer-static-method expr))
;
;(defmethod check :static-method
;  [expr]
;  (let [expected-type (::+T expr)
;        _ (assert expected-type "Static method in checking mode requires annotation")
;
;        inferred-expr (infer-static-method expr)
;
;        actual-type (::+T inferred-expr)
;        _ (assert-subtype actual-type expected-type)]
;    expr))

(comment

  (with-type-anns
    {a Keyword}
    (check-form (def a 1)))

  (with-type-anns
    {str [Object -> String]
     a [Integer -> String]}
    (check-form (defn a [b]
                  (str 1))))

  (with-type-anns
    {str [& Object -> String]
     a [Integer -> String]}
    (check-form (defn a [b]
                  (str "a" 1))))
  (with-type-anns
    {str [& Object -> String]
     clojure.lang.Util/equiv [Number Number -> Boolean]
     a [Integer -> String]}
    (synthesize-form (defn a [b]
                       (if (= b 1)
                         "a"))))

  (with-type-anns
    {ret-fn [-> [-> nil]]}
    (check-form 
      (defn ret-fn []
        (fn []))))

  (with-type-anns
    {test-let [Integer -> Boolean]}
    (check-form 
      (defn test-let [a]
        (let [b true]
          b))))

  (with-type-anns
    {test-let [Integer -> Boolean]}
    (check-form 
      (defn test-let [a]
        (loop [b true]
          b))))

  (with-type-anns
    {var-occ [Any -> Boolean]
     arg-not-nil [Object -> Boolean]}
    (check-form 
      (defn var-occ [a]
        (when a
          (arg-not-nil a)))))

  (with-type-anns
    {identity (All [a]
                   [a -> a])
     id-long [Long -> Long]}
    (synthesize-form
      (defn id-long [a]
        (identity a))))

  (with-type-anns
    {inter [(I clojure.lang.Seqable
               clojure.lang.IPersistentCollection)
            -> nil]}
    (synthesize-form
      (do
        (inter '{})
        (inter '())
        (inter []))))

  (with-type-anns
    {float? (predicate Float)
     integer? (predicate Integer)
     takes-float [Float -> Boolean]
     takes-integer [Integer -> Boolean]
     occur [(U Float Integer) -> Boolean]}
    (synthesize-form 
      (do
        (declare takes-integer takes-float)
        (defn occur [a]
          (cond
            (float? a) (takes-float a)
            (integer? a) (takes-integer a)
            :else false)))))

  (with-type-anns
    {identity (All [a]
                   [a -> a])}
    (synthesize-form
      (do
        (defn identity [b]
          b)
        (identity 1))))

  (with-type-anns
    {both-same (All [a]
                    [a a -> a])}
    (synthesize-form
      (do
        (declare both-same)
        (both-same 1 "a"))))


  ;; Literals
  (synthesize-form 1)
  (synthesize-form "a")
  (synthesize-form :a)
  (synthesize-form [1])

)
