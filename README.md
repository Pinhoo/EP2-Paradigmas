# EP02 — Query builder funcional em Clojure

**Integrantes:**

- Lucas de Pinho Ribeiro dos Santos
- João Pedro Ferreira da Rocha
- Jefferson Santana Nogueira

## Como executar

Requer Java e o Clojure CLI. O projeto não usa nenhuma biblioteca além do próprio Clojure.

```
clojure -M:run dados/filmes.csv
```

Exemplo de sessão:

```
> QUERY onde genero = "drama" e nota >= 8 | ordenar por nota desc | selecionar titulo, nota
Cidade de Deus; 8.60
Ainda Estou Aqui; 8.20
Central do Brasil; 8.00
> QUERY onde genero = "drama" | media duracao
127.40
> EXIT
```

Comandos: `QUERY <consulta>` executa a consulta; `EXIT` (ou o fim da entrada) encerra.
Qualquer erro é mostrado numa linha começando com `ERRO:` e o prompt volta.

## A forma da AST

Uma consulta é um mapa com uma única chave, `:estagios`, cujo valor é um **vetor de nós**,
na mesma ordem em que os elementos aparecem no texto. Cada nó é um vetor cujo primeiro
elemento é uma palavra-chave com o **tipo do nó**. A AST só tem mapas, vetores,
palavras-chave, números e strings — nenhuma função.

```clojure
{:estagios [nó nó ...]}
```

### Nós de estágio

| Texto | Nó |
|---|---|
| `onde <expr>` | `[:onde expr]` |
| `ordenar por campo` | `[:ordenar-por :campo :asc]` |
| `ordenar por campo desc` | `[:ordenar-por :campo :desc]` |
| `limitar 3` | `[:limitar 3]` |

### Nós finais (só podem ser o último do vetor)

| Texto | Nó |
|---|---|
| `selecionar a, b` | `[:selecionar [:a :b]]` |
| `contar` | `[:contar]` |
| `soma campo` | `[:soma :campo]` |
| `media campo` | `[:media :campo]` |
| `agrupar por campo com <agregacao>` | `[:agrupar-por :campo agregacao]` |

A `agregacao` do `agrupar-por` é um dos nós `[:contar]`, `[:soma :campo]` ou `[:media :campo]`.

### Expressões do `onde`

| Texto | Expressão |
|---|---|
| `campo = literal` | `[:= :campo literal]` (também `:!=`, `:<`, `:<=`, `:>`, `:>=`) |
| `a e b` | `[:e a b]` |
| `a ou b` | `[:ou a b]` |
| `nao a` | `[:nao a]` |

O literal é um inteiro (`8`), um decimal (`8.5`) ou uma string (`"drama"`). Os parênteses
não viram nó: só mudam a forma da árvore. `e` e `ou` associam à esquerda:
`a e b e c` vira `[:e [:e a b] c]`.

### Exemplo

```
onde genero = "drama" e nota >= 8 | ordenar por nota desc | limitar 2 | selecionar titulo, nota
```

```clojure
{:estagios [[:onde [:e [:= :genero "drama"] [:>= :nota 8]]]
            [:ordenar-por :nota :desc]
            [:limitar 2]
            [:selecionar [:titulo :nota]]]}
```

`QUERY` sem nada vira `{:estagios []}`.

### Nó desconhecido

Um nó cujo tipo não existe na linguagem, por exemplo

```clojure
{:estagios [[:onde [:= :genero "drama"]] [:voar 3]]}
```

faz `executar` lançar uma exceção com a mensagem `No matching clause: :voar`. Isso acontece
porque a conferência e a compilação despacham cada nó com um `case` sem ramo padrão.

## O builder

As funções ficam em `consultas.core`. Cada uma recebe uma consulta e devolve outra nova;
a de partida não muda.

```clojure
(def base   (consulta))
(def dramas (onde base [:= :genero "drama"]))
(def dois   (limitar dramas 2))

(= dois (analisar "onde genero = \"drama\" | limitar 2"))   ; => true
(executar dois dados)                                       ; => os dois primeiros dramas
```

Funções: `consulta`, `onde`, `ordenar-por`, `limitar`, `selecionar`, e também `contar`,
`soma`, `media` e `agrupar-por`. `analisar` transforma texto em AST (erro de sintaxe é
`ex-info`) e `executar` recebe `{:esquema [[campo tipo] ...] :registros [...]}` e devolve
os registros, o valor do terminal, um vetor de pares `[chave valor]` para o `agrupar por`
ou `{:erros [...]}` quando a consulta é inválida.

## Os namespaces

| Namespace | Papel |
|---|---|
| `consultas.main` | Laço do terminal (`loop`/`recur`), leitura do arquivo e impressão. Único com entrada e saída e com `doseq`. |
| `consultas.sintaxe` | Texto → tokens → AST. Análise recursiva: cada função recebe os tokens e devolve `[nó tokens-que-sobraram]`. |
| `consultas.compilador` | Confere a AST contra o esquema (campos, tipos, posição dos elementos finais) e compila a AST em funções com `case`. Os estágios compilados são compostos com `reduce` e `comp`. |
| `consultas.core` | Leitura do CSV em registros, o builder, `analisar` e `executar`. |

## Como funciona, resumido

1. `analisar` transforma o texto na AST.
2. `executar` chama `conferir`, que devolve a lista de erros. Se houver algum erro, nenhum
   registro é processado e o resultado é `{:erros [...]}`.
3. `compilar` percorre a AST uma vez e transforma cada nó numa função:
   - `onde` vira `(partial filter predicado)`; cada comparação é
     `(partial comparar teste campo literal)`;
   - `ordenar-por` vira `(partial sort-by campo comparador)` (ordenação estável);
   - `limitar` vira `(partial take n)`;
   - `selecionar` vira `(partial map ...)`;
   - `contar`, `soma` e `media` são `reduce`; `agrupar-por` usa `group-by` e aplica a mesma
     agregação em cada grupo.
4. As funções são compostas com `(reduce (fn [f g] (comp g f)) identity funcoes)`, para que
   rodem na ordem em que aparecem na consulta.
5. A função composta é aplicada aos registros.
