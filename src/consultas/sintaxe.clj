(ns consultas.sintaxe)

;; Transforma o texto de uma consulta na AST.
;;
;; Primeiro o texto vira uma lista de tokens. Cada token e um vetor:
;;   [:palavra "onde"]   [:texto "drama"]   [:simbolo "|"]
;;
;; Depois cada funcao de leitura recebe os tokens e devolve um vetor
;; [no-reconhecido tokens-que-sobraram].

;; Tipos de no que so podem aparecer no fim da consulta.
(def tipos-finais #{:selecionar :contar :soma :media :agrupar-por})

(defn erro [mensagem]
  (throw (ex-info mensagem {:tipo :sintaxe})))

;; ---------------------------------------------------------------
;; Tokens
;; ---------------------------------------------------------------

(defn espaco? [c]
  (Character/isWhitespace c))

(defn especial? [c]
  (contains? #{\" \( \) \| \, \= \! \< \>} c))

(defn letra-de-palavra? [c]
  (and (not (espaco? c)) (not (especial? c))))

(defn separar-tokens [texto]
  (loop [letras (seq texto)
         tokens []]
    (if (empty? letras)
      tokens
      (let [c (first letras)
            resto (rest letras)]
        (cond
          (espaco? c)
          (recur resto tokens)

          ;; texto entre aspas
          (= c \")
          (let [partes (split-with (fn [x] (not= x \")) resto)
                conteudo (first partes)
                depois (second partes)]
            (if (empty? depois)
              (erro "aspas sem fechar")
              (recur (rest depois) (conj tokens [:texto (apply str conteudo)]))))

          (contains? #{\( \) \| \, \=} c)
          (recur resto (conj tokens [:simbolo (str c)]))

          ;; < > ! podem vir seguidos de =
          (and (contains? #{\< \> \!} c) (= (first resto) \=))
          (recur (rest resto) (conj tokens [:simbolo (str c "=")]))

          (= c \!)
          (erro "simbolo invalido: !")

          (contains? #{\< \>} c)
          (recur resto (conj tokens [:simbolo (str c)]))

          :else
          (let [partes (split-with letra-de-palavra? letras)]
            (recur (second partes) (conj tokens [:palavra (apply str (first partes))]))))))))

;; ---------------------------------------------------------------
;; Funcoes de apoio
;; ---------------------------------------------------------------

(defn palavra? [token texto]
  (= token [:palavra texto]))

(defn simbolo? [token texto]
  (= token [:simbolo texto]))

(defn descrever [token]
  (if (nil? token)
    "fim da consulta"
    (second token)))

(defn texto-inteiro? [s]
  (re-matches #"-?\d+" s))

(defn texto-decimal? [s]
  (re-matches #"-?\d+\.\d+" s))

(defn ler-numero [funcao s]
  (try
    (funcao s)
    (catch NumberFormatException e
      (erro (str "numero invalido: " s)))))

(defn ler-campo [tokens]
  (let [t (first tokens)]
    (if (= (first t) :palavra)
      [(keyword (second t)) (rest tokens)]
      (erro (str "nome de campo esperado, veio: " (descrever t))))))

(def operadores {"=" :=, "!=" :!=, "<" :<, "<=" :<=, ">" :>, ">=" :>=})

(defn ler-operador [tokens]
  (let [t (first tokens)]
    (cond
      (nil? t)
      (erro "falta o operador da comparacao")

      (and (= (first t) :simbolo) (contains? operadores (second t)))
      [(get operadores (second t)) (rest tokens)]

      :else
      (erro (str "operador desconhecido: " (descrever t))))))

(defn ler-literal [tokens]
  (let [t (first tokens)]
    (cond
      (nil? t)
      (erro "falta o literal da comparacao")

      (= (first t) :texto)
      [(second t) (rest tokens)]

      (and (= (first t) :palavra) (texto-inteiro? (second t)))
      [(ler-numero (fn [s] (Long/parseLong s)) (second t)) (rest tokens)]

      (and (= (first t) :palavra) (texto-decimal? (second t)))
      [(ler-numero (fn [s] (Double/parseDouble s)) (second t)) (rest tokens)]

      :else
      (erro (str "literal invalido: " (descrever t) " (texto vai entre aspas)")))))

;; ---------------------------------------------------------------
;; Expressoes do onde
;;   ou  -> e [ou e]*
;;   e   -> nao [e nao]*
;;   nao -> nao nao | ( ou ) | campo op literal
;; ---------------------------------------------------------------

(declare ler-ou)

(defn ler-comparacao [tokens]
  (let [[campo resto1] (ler-campo tokens)
        [op resto2] (ler-operador resto1)
        [literal resto3] (ler-literal resto2)]
    [[op campo literal] resto3]))

(defn ler-nao [tokens]
  (cond
    (palavra? (first tokens) "nao")
    (let [[expr resto] (ler-nao (rest tokens))]
      [[:nao expr] resto])

    (simbolo? (first tokens) "(")
    (let [[expr resto] (ler-ou (rest tokens))]
      (cond
        (simbolo? (first resto) ")") [expr (rest resto)]
        (empty? resto) (erro "parentese sem fechar")
        :else (erro (str "esperado ) mas veio: " (descrever (first resto))))))

    :else
    (ler-comparacao tokens)))

;; Junta as proximas expressoes ligadas por "e" (associa a esquerda).
(defn continuar-e [esquerda tokens]
  (if (palavra? (first tokens) "e")
    (let [[direita resto] (ler-nao (rest tokens))]
      (recur [:e esquerda direita] resto))
    [esquerda tokens]))

(defn ler-e [tokens]
  (let [[esquerda resto] (ler-nao tokens)]
    (continuar-e esquerda resto)))

(defn continuar-ou [esquerda tokens]
  (if (palavra? (first tokens) "ou")
    (let [[direita resto] (ler-e (rest tokens))]
      (recur [:ou esquerda direita] resto))
    [esquerda tokens]))

(defn ler-ou [tokens]
  (let [[esquerda resto] (ler-e tokens)]
    (continuar-ou esquerda resto)))

;; ---------------------------------------------------------------
;; Elementos da consulta
;; ---------------------------------------------------------------

(defn esperar-palavra [tokens palavra mensagem]
  (if (palavra? (first tokens) palavra)
    (rest tokens)
    (erro mensagem)))

(defn ler-lista-de-campos [tokens]
  (let [[campo resto] (ler-campo tokens)]
    (if (simbolo? (first resto) ",")
      (let [[outros resto2] (ler-lista-de-campos (rest resto))]
        [(vec (cons campo outros)) resto2])
      [[campo] resto])))

(defn ler-agregacao [tokens]
  (let [t (first tokens)]
    (cond
      (palavra? t "contar")
      [[:contar] (rest tokens)]

      (palavra? t "soma")
      (let [[campo resto] (ler-campo (rest tokens))]
        [[:soma campo] resto])

      (palavra? t "media")
      (let [[campo resto] (ler-campo (rest tokens))]
        [[:media campo] resto])

      :else
      (erro (str "agregacao desconhecida: " (descrever t))))))

(defn ler-elemento [tokens]
  (let [t (first tokens)]
    (cond
      (or (nil? t) (simbolo? t "|"))
      (erro "estagio vazio")

      (palavra? t "onde")
      (let [[expr resto] (ler-ou (rest tokens))]
        [[:onde expr] resto])

      (palavra? t "ordenar")
      (let [resto (esperar-palavra (rest tokens) "por" "falta \"por\" depois de ordenar")
            [campo resto2] (ler-campo resto)]
        (if (palavra? (first resto2) "desc")
          [[:ordenar-por campo :desc] (rest resto2)]
          [[:ordenar-por campo :asc] resto2]))

      (palavra? t "limitar")
      (let [n (second tokens)]
        (if (and (= (first n) :palavra) (re-matches #"\d+" (second n)))
          [[:limitar (ler-numero (fn [s] (Long/parseLong s)) (second n))] (rest (rest tokens))]
          (erro (str "numero esperado depois de limitar, veio: " (descrever n)))))

      (palavra? t "selecionar")
      (let [[campos resto] (ler-lista-de-campos (rest tokens))]
        [[:selecionar campos] resto])

      (or (palavra? t "contar") (palavra? t "soma") (palavra? t "media"))
      (ler-agregacao tokens)

      (palavra? t "agrupar")
      (let [resto (esperar-palavra (rest tokens) "por" "falta \"por\" depois de agrupar")
            [campo resto2] (ler-campo resto)
            resto3 (esperar-palavra resto2 "com" "falta \"com <agregacao>\" depois de agrupar por")
            [agregacao resto4] (ler-agregacao resto3)]
        [[:agrupar-por campo agregacao] resto4])

      :else
      (erro (str "estagio desconhecido: " (descrever t))))))

(defn ler-elementos [tokens estagios]
  (let [[no resto] (ler-elemento tokens)
        novos-estagios (conj estagios no)]
    (cond
      (empty? resto)
      novos-estagios

      (not (simbolo? (first resto) "|"))
      (erro (str "nao esperado: " (descrever (first resto))))

      (contains? tipos-finais (first no))
      (erro (str (name (first no)) " tem que ser o ultimo elemento da consulta"))

      :else
      (recur (rest resto) novos-estagios))))

(defn analisar [texto]
  (let [tokens (separar-tokens texto)]
    (if (empty? tokens)
      {:estagios []}
      {:estagios (ler-elementos tokens [])})))
