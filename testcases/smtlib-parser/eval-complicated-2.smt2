(set-logic QF_LIA)
(declare-fun x () Int)
(declare-fun y () Int)
(check-sat)
(get-value (x y (+ x y 1)))
(assert (> x 0))
(check-sat)
(get-value (x y (+ x y 1)))
