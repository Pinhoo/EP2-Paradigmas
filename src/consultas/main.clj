(ns consultas.main
  (:require [clojure.string :as str]
            [consultas.core :as core]))

;; Unico namespace com entrada e saida.

(defn formatar [valor]
  (if (float? valor)
    (String/format java.util.Locale/ROOT "%.2f" (to-array [valor]))
    (str valor)))

(defn mensagem-de-erro [e]
  (if (nil? (.getMessage e))
    (str (class e))
    (.getMessage e)))

;; Sem selecionar no fim, as colunas saem na ordem do esquema.
(defn colunas-da-saida [consulta esquema]
  (let [ultimo (last (:estagios consulta))]
    (if (= (first ultimo) :selecionar)
      (second ultimo)
      (map first esquema))))

(defn imprimir-registros [registros colunas]
  (doseq [registro registros]
    (println (str/join "; " (map (fn [coluna] (formatar (get registro coluna))) colunas)))))

(defn imprimir-grupos [grupos]
  (doseq [[chave valor] grupos]
    (println (str (formatar chave) "; " (formatar valor)))))

(defn imprimir-resultado [consulta dados resultado]
  (let [tipo-final (first (last (:estagios consulta)))]
    (cond
      (map? resultado)
      (println (str "ERRO: " (str/join "; " (:erros resultado))))

      (contains? #{:contar :soma :media} tipo-final)
      (println (formatar resultado))

      (empty? resultado)
      (println "(vazio)")

      (= tipo-final :agrupar-por)
      (imprimir-grupos resultado)

      :else
      (imprimir-registros resultado (colunas-da-saida consulta (:esquema dados))))))

(defn executar-query [texto dados]
  (try
    (let [consulta (core/analisar texto)
          resultado (core/executar consulta dados)]
      (imprimir-resultado consulta dados resultado))
    (catch Throwable e
      (println (str "ERRO: " (mensagem-de-erro e))))))

(defn responder [linha dados]
  (cond
    (str/blank? linha)
    (println "ERRO: linha em branco")

    (= linha "QUERY")
    (executar-query "" dados)

    (str/starts-with? linha "QUERY ")
    (executar-query (subs linha 6) dados)

    :else
    (println (str "ERRO: comando desconhecido: " linha))))

;; Laco do terminal: le uma linha, responde e volta ao comeco.
(defn terminal [dados]
  (loop []
    (print "> ")
    (flush)
    (let [linha (read-line)]
      (if (or (nil? linha) (= (str/trim linha) "EXIT"))
        nil
        (do
          (responder (str/trim linha) dados)
          (recur))))))

(defn carregar [caminho]
  (try
    (core/ler-csv (slurp caminho :encoding "UTF-8"))
    (catch Throwable e
      (println (str "ERRO: nao foi possivel ler " caminho ": " (mensagem-de-erro e)))
      nil)))

(defn -main [& args]
  (if (empty? args)
    (println "uso: clojure -M:run <arquivo.csv>")
    (let [dados (carregar (first args))]
      (if (nil? dados)
        nil
        (terminal dados)))))
