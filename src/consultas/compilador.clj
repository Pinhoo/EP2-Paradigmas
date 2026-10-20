(ns consultas.compilador
  (:require [consultas.sintaxe :as sintaxe]))

;; Duas partes:
;;   1. conferir: confere a AST contra o esquema e devolve a lista de erros.
;;   2. compilar: transforma a AST em uma unica funcao, que recebe os
;;      registros e devolve o resultado.
;;
;; Os dois despacham cada no com um case sem ramo padrao: um no que nao
;; existe na linguagem faz o case falhar com "No matching clause: <no>".

;; ---------------------------------------------------------------
;; Conferencia contra o esquema
;; ---------------------------------------------------------------

;; esquema e um vetor de pares [campo tipo]
(defn tipo-do-campo [esquema campo]
  (some (fn [par] (if (= (first par) campo) (second par))) esquema))

(defn numerico? [tipo]
  (or (= tipo :inteiro) (= tipo :decimal)))

(defn tipo-do-literal [valor]
  (cond
    (integer? valor) :inteiro
    (number? valor) :decimal
    (string? valor) :texto
    :else :desconhecido))

(defn conferir-campo [esquema campo]
  (if (tipo-do-campo esquema campo)
    []
    [(str "campo inexistente: " (name campo))]))

(defn conferir-campo-numerico [esquema campo operacao]
  (let [tipo (tipo-do-campo esquema campo)]
    (cond
      (nil? tipo) [(str "campo inexistente: " (name campo))]
      (numerico? tipo) []
      :else [(str operacao " exige campo numerico, mas " (name campo) " e " (name tipo))])))

(defn conferir-comparacao [esquema campo literal]
  (let [tipo (tipo-do-campo esquema campo)
        tipo-literal (tipo-do-literal literal)]
    (cond
      (nil? tipo)
      [(str "campo inexistente: " (name campo))]

      (= tipo-literal :desconhecido)
      [(str "literal invalido no campo " (name campo))]

      (or (= tipo tipo-literal) (and (numerico? tipo) (numerico? tipo-literal)))
      []

      :else
      [(str "comparacao entre " (name tipo) " e " (name tipo-literal) " no campo " (name campo))])))

(defn conferir-expr [expr esquema]
  (case (first expr)
    (:e :ou) (concat (conferir-expr (nth expr 1) esquema)
                     (conferir-expr (nth expr 2) esquema))
    :nao (conferir-expr (nth expr 1) esquema)
    (:= :!= :< :<= :> :>=) (conferir-comparacao esquema (nth expr 1) (nth expr 2))))

(defn conferir-agregacao [agregacao esquema]
  (case (first agregacao)
    :contar []
    :soma (conferir-campo-numerico esquema (nth agregacao 1) "soma")
    :media (conferir-campo-numerico esquema (nth agregacao 1) "media")))

(defn conferir-estagio [estagio esquema]
  (case (first estagio)
    :onde (conferir-expr (nth estagio 1) esquema)
    :ordenar-por (concat (conferir-campo esquema (nth estagio 1))
                         (if (contains? #{:asc :desc} (get estagio 2))
                           []
                           ["a ordem tem que ser :asc ou :desc"]))
    :limitar (if (and (integer? (get estagio 1)) (>= (get estagio 1) 0))
               []
               ["limitar exige um inteiro maior ou igual a zero"])
    :selecionar (mapcat (fn [campo] (conferir-campo esquema campo)) (nth estagio 1))
    (:contar :soma :media) (conferir-agregacao estagio esquema)
    :agrupar-por (concat (conferir-campo esquema (nth estagio 1))
                         (conferir-agregacao (nth estagio 2) esquema))))

;; selecionar, contar, soma, media e agrupar por so podem ser o ultimo.
(defn conferir-ordem [estagios]
  (mapcat (fn [estagio]
            (if (contains? sintaxe/tipos-finais (first estagio))
              [(str (name (first estagio)) " tem que ser o ultimo elemento da consulta")]
              []))
          (butlast estagios)))

(defn conferir [consulta esquema]
  (let [estagios (:estagios consulta)]
    (vec (concat (mapcat (fn [estagio] (conferir-estagio estagio esquema)) estagios)
                 (conferir-ordem estagios)))))

;; ---------------------------------------------------------------
;; Compilacao das expressoes: cada expressao vira um predicado
;; ---------------------------------------------------------------

;; O operador vira um teste sobre o resultado de compare.
(defn teste-do-operador [op]
  (case op
    := (fn [c] (= c 0))
    :!= (fn [c] (not= c 0))
    :< (fn [c] (< c 0))
    :<= (fn [c] (<= c 0))
    :> (fn [c] (> c 0))
    :>= (fn [c] (>= c 0))))

(defn comparar [teste campo literal registro]
  (teste (compare (get registro campo) literal)))

(defn compilar-expr [expr]
  (case (first expr)
    :e (let [a (compilar-expr (nth expr 1))
             b (compilar-expr (nth expr 2))]
         (fn [registro] (and (a registro) (b registro))))
    :ou (let [a (compilar-expr (nth expr 1))
              b (compilar-expr (nth expr 2))]
          (fn [registro] (or (a registro) (b registro))))
    :nao (let [a (compilar-expr (nth expr 1))]
           (fn [registro] (not (a registro))))
    (:= :!= :< :<= :> :>=) (partial comparar
                                    (teste-do-operador (first expr))
                                    (nth expr 1)
                                    (nth expr 2))))

;; ---------------------------------------------------------------
;; Agregacoes: todas sao reduce
;; ---------------------------------------------------------------

(defn contar [registros]
  (reduce (fn [total registro] (inc total)) 0 registros))

(defn somar [zero campo registros]
  (reduce (fn [total registro] (+ total (get registro campo))) zero registros))

(defn media [campo registros]
  (let [[soma quantidade] (reduce (fn [[soma n] registro]
                                    [(+ soma (get registro campo)) (inc n)])
                                  [0 0]
                                  registros)]
    (if (= quantidade 0)
      0.0
      (double (/ soma quantidade)))))

;; Devolve um vetor de pares [chave valor], em ordem crescente de chave.
(defn agrupar [campo agregar registros]
  (let [grupos (group-by (fn [registro] (get registro campo)) registros)
        chaves (sort (keys grupos))]
    (mapv (fn [chave] [chave (agregar (get grupos chave))]) chaves)))

;; Soma de campo decimal comeca em 0.0, para sair como decimal mesmo vazia.
(defn zero-do-campo [esquema campo]
  (if (= (tipo-do-campo esquema campo) :decimal) 0.0 0))

(defn compilar-agregacao [agregacao esquema]
  (case (first agregacao)
    :contar contar
    :soma (partial somar (zero-do-campo esquema (nth agregacao 1)) (nth agregacao 1))
    :media (partial media (nth agregacao 1))))

;; ---------------------------------------------------------------
;; Compilacao dos estagios
;; ---------------------------------------------------------------

(defn comparador-da-ordem [ordem]
  (case ordem
    :asc compare
    :desc (fn [a b] (compare b a))))

(defn so-os-campos [campos registro]
  (select-keys registro campos))

(defn compilar-estagio [estagio esquema]
  (case (first estagio)
    :onde (partial filter (compilar-expr (nth estagio 1)))
    :ordenar-por (partial sort-by (nth estagio 1) (comparador-da-ordem (nth estagio 2)))
    :limitar (partial take (nth estagio 1))
    :selecionar (partial map (partial so-os-campos (nth estagio 1)))
    (:contar :soma :media) (compilar-agregacao estagio esquema)
    :agrupar-por (partial agrupar
                          (nth estagio 1)
                          (compilar-agregacao (nth estagio 2) esquema))))

;; Compoe os estagios na ordem em que aparecem na consulta.
;; (comp g f) aplica f primeiro, por isso o novo estagio vai na esquerda.
(defn compilar [consulta esquema]
  (let [funcoes (mapv (fn [estagio] (compilar-estagio estagio esquema))
                      (:estagios consulta))]
    (reduce (fn [f g] (comp g f)) identity funcoes)))
