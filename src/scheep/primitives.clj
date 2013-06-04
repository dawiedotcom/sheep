(ns scheep.primitives
  (:gen-class)
  (:use
   [scheep.env :only [extend-environment
                      the-empty-environment]]))

;;; Scheme's standard procedures that are implemented in terms
;;; of clojure functions. See
;;; [1]: http://www.schemers.org/Documents/Standards/R5RS/HTML/r5rs-Z-H-9.html
;;; [2]: http://en.wikipedia.org/wiki/Scheme_programming_language#Standard_procedures

;;; A representation of primitive procedures

(defrecord primitive-procedure [implementation])
(defn primitive-procedure? [p] (instance? primitive-procedure p))
(defn apply-primitive-procedure [proc args]
  (apply
   (.implementation proc)
   args))

;;; An environment that maps the names of primitive procedures
;;; to their clojure implementations

(declare primitive-procedure-names
         primitive-procedure-objects)

(defn the-primitive-environment []
  (extend-environment
   the-empty-environment
   (primitive-procedure-names)
   (primitive-procedure-objects)))

;;; A map of base scheme procedures that are implemented in
;;; terms of clojure functions.

(def primitive-procedures
  {'car first
   'cdr rest
   'null? empty?
   '+ +
   '- -
   '* *
   '/ /
   '= =
   '> >
   '< <
   ;'load scheme-load
   'list list
   'display print
   'newline #(println)
   'cons #(cons %1 (if (seq? %2)
                     %2
                     (list %2)))
   })

;;; Helpers

(defn primitive-procedure-names []
  (keys primitive-procedures))
(defn primitive-procedure-objects []
  (map #(primitive-procedure. %)
       (vals primitive-procedures)))
 
