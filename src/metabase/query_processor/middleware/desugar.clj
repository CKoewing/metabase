(ns metabase.query-processor.middleware.desugar
  (:require [metabase.mbql
             [schema :as mbql.s]
             [util :as mbql.u]]
            [schema.core :as s]))

(defn- desugar-inside [query]
  (mbql.u/replace-clauses-in query [:query :filter] :inside
    (fn [[_ lat-field lon-field lat-max lon-min lat-min lon-max]]
      [:and
       [:between lat-field lat-min lat-max]
       [:between lon-field lon-min lon-max]])))

(defn- desugar-is-null-and-not-null [query]
  (mbql.u/replace-clauses-in query [:query :filter] #{:is-null :not-null}
    (fn [[clause-name field]]
      (case clause-name
        :is-null  [:=  field nil]
        :not-null [:!= field nil]))))

(defn- desugar-time-interval [query]
  (mbql.u/replace-clauses-in query [:query :filter] :time-interval
    (fn [[_ field n unit options]]
      (if-not (integer? n)
        (case n
          :current (recur [nil field  0 unit options])
          :last    (recur [nil field -1 unit options])
          :next    (recur [nil field  1 unit options]))
        (let [f                [:datetime-field field unit]
              include-current? (:include-current options)]
          (cond
            (= n  0) [:= field [:relative-datetime  0 unit]]
            (= n -1) [:= field [:relative-datetime -1 unit]]
            (= n  1) [:= field [:relative-datetime  1 unit]]
            (< n -1) [:between field
                      [:relative-datetime                          n unit]
                      [:relative-datetime (if include-current? 0 -1) unit]]
            (> n  1) [:between field
                      [:relative-datetime (if include-current? 0  1) unit]
                      [:relative-datetime                          n unit]]))))))

(defn- desugar-does-not-contain [query]
  (mbql.u/replace-clauses-in query [:query :filter] :does-not-contain
    (fn [[_ & args]]
      [:not (vec (cons :contains args))])))


(defn- desugar-equals-and-not-equals-with-extra-args
  "`:=` and `!=` clauses with more than 2 args automatically get rewritten as compound filters.

     [:= field x y]  -> [:or  [:=  field x] [:=  field y]]
     [:!= field x y] -> [:and [:!= field x] [:!= field y]]"
  [query]
  (mbql.u/replace-clauses-in query [:query :filter] #{:= :!=}
    (fn [[clause-name field x & more, :as filter-clause]]
      (if-not (seq more)
        filter-clause
        (let [compound-clause (case clause-name
                                :=  :or
                                :!= :and)]
          (vec
           (cons
            compound-clause
            (for [x (cons x more)]
              [clause-name field x]))))))))


(s/defn ^:private desugar* :- mbql.s/Query
  [query]
  (-> query
      desugar-inside
      desugar-is-null-and-not-null
      desugar-time-interval
      desugar-does-not-contain
      desugar-equals-and-not-equals-with-extra-args))

(defn desugar [qp]
  (comp qp desugar*))
