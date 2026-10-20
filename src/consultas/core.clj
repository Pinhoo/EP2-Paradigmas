(ns consultas.core
  (:require [clojure.string :as str]
            [consultas.sintaxe :as sintaxe]
            [consultas.compilador :as compilador]))

;; ---------------------------------------------------------------
;; Leitura do CSV
;; ---------------------------------------------------------------

(def tipos-validos #{:inteiro :decimal :texto})

(defn converter-valor [tipo texto]
  (case tipo
    :inteiro (Long/parseLong texto)
    :decimal (Double/parseDouble texto)
    :texto texto))

;; "a:texto;b:inteiro" -> [[:a :texto] [:b :inteiro]]
(defn ler-cabecalho [linha]
  (mapv (fn [coluna]
          (let [partes (str/split coluna #":")
                campo (first partes)
                tipo (keyword (second partes))]
            (if (contains? tipos-validos tipo)
              [(keyword campo) tipo]
              (throw (ex-info (str "tipo invalido no cabecalho: " coluna) {})))))
        (str/split linha #";")))

(defn ler-registro [esquema linha]
  (let [valores (str/split linha #";")]
    (if (not= (count valores) (count esquema))
      (throw (ex-info (str "linha com quantidade errada de campos: " linha) {}))
      (zipmap (map first esquema)
              (map (fn [par valor] (converter-valor (second par) valor)) esquema valores)))))

;; Recebe o texto inteiro do arquivo e devolve {:esquema [...] :registros [...]}
(defn ler-csv [texto]
  (let [linhas (remove str/blank? (str/split-lines (str/replace texto "﻿" "")))
        esquema (ler-cabecalho (first linhas))]
    {:esquema esquema
     :registros (mapv (fn [linha] (ler-registro esquema linha)) (rest linhas))}))

;; ---------------------------------------------------------------
;; Builder: cada funcao recebe uma consulta e devolve outra nova
;; ---------------------------------------------------------------

(defn consulta []
  {:estagios []})

(defn acrescentar [c estagio]
  (update c :estagios conj estagio))

(defn onde [c expr]
  (acrescentar c [:onde expr]))

(defn ordenar-por [c campo ordem]
  (acrescentar c [:ordenar-por campo ordem]))

(defn limitar [c n]
  (acrescentar c [:limitar n]))

(defn selecionar [c campos]
  (acrescentar c [:selecionar (vec campos)]))

(defn contar [c]
  (acrescentar c [:contar]))

(defn soma [c campo]
  (acrescentar c [:soma campo]))

(defn media [c campo]
  (acrescentar c [:media campo]))

;; agregacao e [:contar], [:soma campo] ou [:media campo]
(defn agrupar-por [c campo agregacao]
  (acrescentar c [:agrupar-por campo agregacao]))

;; ---------------------------------------------------------------
;; Analise e execucao
;; ---------------------------------------------------------------

(defn analisar [texto]
  (sintaxe/analisar texto))

(defn executar [c dados]
  (let [erros (compilador/conferir c (:esquema dados))]
    (if (empty? erros)
      (let [funcao (compilador/compilar c (:esquema dados))]
        (funcao (:registros dados)))
      {:erros erros})))
