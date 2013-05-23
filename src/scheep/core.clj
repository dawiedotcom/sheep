(ns scheep.core
  (:gen-class))

;;;; Forward declarations

(declare scheme-eval
         scheme-apply
         set-variable-value!
         define-variable!
         scheme-true?
         make-compound-procedure
         make-lambda)
         
;;;; A map of eval function for scheme special forms

(def ^:dynamic *evals* (ref {}))

(defn get-form-symbol [exp] (keyword (first exp)))

(defn get-eval [exp]
  ((get-form-symbol exp) @*evals*))

(defn has-eval? [exp]
  (contains? @*evals* (get-form-symbol exp)))

(defn register-eval [symbol function]
  (dosync
   (alter *evals* #(assoc % symbol function))))

;;;; Expression Types ;;;;

(defn tagged-list? [exp tag]
  (if (list? exp)
      (= (first exp) tag)
      false))

(defmacro defeval
  "A macro that defines and registers a fn to eval
  a scheme special form"
  [symbol params & body]
  `(register-eval
    ~(keyword symbol)
    (fn ~params ~@body)))
  
;;;; Self evaluating expressions

(defn self-evaluating? [exp]
  (or (string? exp)
      (number? exp)
      (= true exp)
      (= false exp)))

;;;; Variables

(defn variable? [exp]
  (or (symbol? exp)
      (= exp true)
      (= exp false)))

;;;; Quotes
(defeval quote [[_ text-of-quotation] env]
  text-of-quotation)
  
;;; Assignment
(defeval set! [[_ assignment-variable assignment-value] env]
  (set-variable-value!
   env
   assignment-variable
   (scheme-eval assignment-value env))
  'ok)
  
;;;; Definitions
(defn procedure-definition? [variable]
  (list? variable))
(defn procedure-arguments [[_ & args]] args)
(defn procedure-name [[name]] name)
                                   
(defeval define [[_ definition-variable definition-value] env]
  (let [name-clause definition-variable]
    (if (procedure-definition? name-clause)
      (define-variable!
        env
        (procedure-name name-clause)
        (make-compound-procedure      
         (procedure-arguments name-clause)
         (list definition-value)
         env))
      (define-variable!
        env
        definition-variable
        (scheme-eval definition-value env)))
    'ok))

;;;; Conditionals
(defeval if [[_ if-predicate if-consequent if-alternative] env]
  (if (scheme-true? (scheme-eval if-predicate env))
    (scheme-eval if-consequent env)
    (scheme-eval if-alternative env)))

(defn make-if [predicate consequent alternative]
  (list 'if predicate consequent alternative))

;;;; Lambda expressions
(defeval lambda [[_ lambda-parameters & lambda-body] env]
  (make-compound-procedure
   lambda-parameters
   lambda-body
   env))

(defn make-lambda [params body]
  (list 'lambda params body))

;;;; Begin

(defn last-exp? [exps] (empty? (rest exps)))
(defn first-exp [[exp]] exp)
(defn rest-exp [[_ & rest]] rest)

(defn eval-sequence [exps env]
  (loop [es exps]
    (if (last-exp? es)
        (scheme-eval (first-exp es) env)
        (do
          (scheme-eval (first-exp es) env)
          (recur (rest-exp es))))))
        
(defeval begin [[_ & begin-actions] env]
  (eval-sequence begin-actions env))

(defn make-begin [seq]
  (conj seq 'begin))

(defn sequence->exp [[first-exp & rest-exps]]
  ;; Wraps a sequence of expressions in a begin expression
  (cond
   (nil? first-exp) '()
   (nil? rest-exps) first-exp
   :else (make-begin seq)))
  
;;;; Procedure application

(defn application? [exp]
  (list? exp))

(defn operator [[opr]] opr)
(defn operands [[_ & args]] args)

(defn no-operands? [ops] (empty? ops))
(defn first-operand [[op]] op)
(defn rest-operands [[_ & ops]] ops)

;;;; Derived expressions ;;;;

;;;; Cond

(defn cond? [exp] (tagged-list? exp 'cond))
(defn cond-clauses [[_ & clauses]] clauses)

(defn cond-predicate [[pred]] pred)
(defn cond-actions [[_ & actions]] actions)
(defn cond-else-clause? [clause]
  (= (cond-predicate clause) 'else))

(defn expand-clauses [[first-clause & rest-clauses]]
  (if (nil? first-clause)
      'false                 ; cond with no else
      (if (cond-else-clause? first-clause)
          (if (empty? rest-clauses)
              (sequence->exp (cond-actions first-clause))
              (throw (Exception. "else clause isn't the last clause")))
          (make-if (cond-predicate first-clause)
                   (sequence->exp (cond-actions first-clause))
                   (expand-clauses rest-clauses)))))
  
(defn cond->if [exp]
  (expand-clauses (cond-clauses exp)))

;;;; Evaluator data strucures ;;;;

;;;; Truthyness

(defn scheme-true? [x]
  (not (= x false)))
(defn scheme-false? [x]
  (= x false))

;;;; Primitive procedures

(defrecord primitive-procedure [implementation])
(defn primitive-procedure? [p] (instance? primitive-procedure p))
(defn apply-primitive-procedure [proc args]
  (apply
   (.implementation proc)
   args))

;;;; Compound procedures

(defrecord compound-procedure [parameters body env])
(defn make-compound-procedure [params body env]
  ; this will have to do since we cannot declare record names..
  (compound-procedure. params body env))
(defn compound-procedure? [p] (instance? compound-procedure p))

;;;; Frames 

(defn make-frame [variables values]
  (ref (zipmap variables values)))
(defn frame-add-binding! [frame var val]
  (dosync
   (alter frame #(assoc % var val))))
(defn frame-get-binding [frame var]
  (@frame var))
(defn frame-defined? [frame var]
  (contains? @frame var))

(defn print-frame [frame]
  (println)
  (doseq [[var val] @frame]
    (println (str "\t" var " => " val)))
  (println))

;;;; Environments 

(def the-empty-environment nil)
(defn the-empty-environment? [env]
  (empty? env))

(defn first-frame [env] (first env))
(defn enclosing-environment [env] (next env))
(defn extend-environment [env vars vals] 
  (conj env (make-frame vars vals)))

(defn with-var-in-env [proc var env] 
  (letfn [(lookup [e]
            (if (the-empty-environment? e)
                (Exception. (str "Variable " var " is not defined\n"))
                (let [frame (first-frame e)]
                  ;(println (str "Looking for " var " in:"))
                  ;(print-frame frame)
                  (if (frame-defined? frame var)
                      (proc frame var)
                      (recur (enclosing-environment e))))))]
    (lookup env)))

(defn set-variable-value! [env var val]
  (with-var-in-env #(frame-add-binding! %1 %2 val) var env))
(defn lookup-variable-value [exp env]
  ;; Called by eval, only if exp is a variable
  (with-var-in-env frame-get-binding exp env))
(defn define-variable! [env var val] 
  (if (the-empty-environment? env)
      (throw (.Exception "Cannot define variables in the-empty-environment"))
      (let [frame (first-frame env)]
        (frame-add-binding! frame var val))))

;;;; Eval ;;;;
    
(defn list-of-values [exps env]
  (loop [es exps
         acc (list)]
    (if (no-operands? es)
        (reverse acc)
        (recur (rest-operands es)
               (cons (scheme-eval (first-operand es) env)
                     acc)))))

(defn scheme-eval [exp env]
  (cond 
   (self-evaluating? exp) exp
   (variable? exp) (lookup-variable-value exp env)
   (has-eval? exp) (let
                       [eval-proc (get-eval exp)]
                     (eval-proc exp env))
   (cond? exp) (scheme-eval
                (cond->if exp)
                env)
   (application? exp) (scheme-apply
                       (scheme-eval (operator exp) env)
                       (list-of-values (operands exp) env))
   :else (throw
          (Exception. (str "Unknown expression type " exp "\n")))))

;;;; Apply ;;;;

(defn scheme-apply [procedure arguments]
  ;(println (str "scheme-apply arguments:" arguments))
  (cond (primitive-procedure? procedure) (apply-primitive-procedure
                                          procedure
                                          arguments)
        (compound-procedure? procedure) (eval-sequence
                                         (.body procedure)
                                         (extend-environment
                                          (.env procedure)
                                          (.parameters procedure)
                                          arguments))
       :else (throw
               (Exception. (str "Unknown procedure type " procedure "\n")))))
        
;;;; REPL ;;;;

;;;; The global environment

(def primitive-procedures
  (list (list 'car first)
        (list 'cdr rest)
        (list 'null? empty?)
        (list 'cons cons)
        (list '+ +)
        (list '- -)
        (list '* *)
        (list '/ /)
        (list '= =)
        (list 'list list)
        (list 'display print)
        (list 'newline (fn [] (println)))))

(defn primitive-procedure-names [] (map first primitive-procedures))
(defn primitive-procedure-objects []
  (map (fn [[_ p]] (primitive-procedure. p))
       primitive-procedures))
  
(defn setup-environment []
  (let [initial-env
        (extend-environment
         the-empty-environment
         (primitive-procedure-names)
         (primitive-procedure-objects))]
    (define-variable! initial-env 'true true)
    (define-variable! initial-env 'false false)
    initial-env))

;;;; repl proper

(def input-prompt "; scheep> ")
(def output-prompt ";= ")

(defn prompt-for-input [prompt]
  (print (str "\n" prompt))
  (flush))
(defn user-str [output] (str output))
(defn print-output [prompt output]
  (println (str prompt (user-str output))))
  
(defn driver-loop [global-env]
  (prompt-for-input input-prompt)
  (let [input (read *in* false nil)]
    (if input
      (let [output (scheme-eval input global-env)]
        (print-output output-prompt output)
        (recur global-env))
      (println))))
    
(def the-global-environment (setup-environment))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (driver-loop the-global-environment))
