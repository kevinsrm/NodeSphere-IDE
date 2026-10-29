# Terminal + Node.js: OpenSSL, variáveis de ambiente e terminal com seleção

Documento técnico desta rodada de correções no NodeSphere IDE (`com.mobile.nodeide`).
Serve como guia de validação no aparelho e como contexto para quem continuar o trabalho.

---

## 1. Os pedidos do prompt, item por item

### 1.1 Caminho do OpenSSL e variáveis de ambiente do Node — **feito**

**Sintoma real:** o `node`/`npm` compilado no Termux procura
`/data/data/com.termux/files/usr/etc/tls/openssl.cnf` (prefixo hardcoded). Como o
pacote deste app é `com.mobile.nodeide`, essa pasta não existe no sandbox do app e
o OpenSSL responde "Permission denied", travando `node` e `npm`.

**Correção definitiva:**

- `NodeRuntime.processBuilder(comando, diretório)` é o **único** ponto de criação
  de processos do app. O shell (`TerminalSession`), o `run()` (versões/diagnóstico)
  e o agente (`AgentFragment`) passaram a usá-lo. Ninguém mais chama
  `ProcessBuilder(...)` direto.
- `processBuilder` chama `prepare()` antes do `start()` e injeta
  `environment(diretório)` no `builder.environment()`.
- `prepare()` cria `files/usr/etc/tls/` (e `certs/`) e extrai dos assets do app:
  - `assets/runtime/openssl.cnf` → `files/usr/etc/tls/openssl.cnf`, com `@PREFIX@`
    e `@HOME@` substituídos pelos caminhos reais do app;
  - `assets/runtime/cert.pem` (bundle de CAs da Mozilla) → `files/usr/etc/tls/cert.pem`.
- Se um runtime importado trouxer um `openssl.cnf` com o prefixo do Termux,
  `rewriteTermuxTlsPaths()` reescreve essas referências para o prefixo do app
  (em vez de sobrescrever a configuração do usuário).

Variáveis injetadas em **todo** processo filho (`<filesDir>` = diretório privado
do app, `/data/user/0/com.mobile.nodeide/files` no usuário principal):

| Variável | Valor |
| --- | --- |
| `PREFIX` | `<filesDir>/usr` |
| `HOME` | `<filesDir>/home` |
| `TMPDIR` | `<filesDir>/usr/tmp` |
| `OPENSSL_CONF` | `<filesDir>/usr/etc/tls/openssl.cnf` |
| `SSL_CERT_FILE` | `<filesDir>/usr/etc/tls/cert.pem` |
| `SSL_CERT_DIR` | `<filesDir>/usr/etc/tls/certs` |
| `NODE_EXTRA_CA_CERTS` / `CURL_CA_BUNDLE` / `GIT_SSL_CAINFO` / `NPM_CONFIG_CAFILE` | mesmo `cert.pem` |
| `PATH` | `<filesDir>/usr/bin:<PATH do sistema>` |
| `LD_LIBRARY_PATH` | `<filesDir>/usr/lib:<nativeLibraryDir>` |
| `NODE_PATH` | `<filesDir>/usr/lib/node_modules` |
| `NPM_CONFIG_CACHE/PREFIX/TMP/USERCONFIG` | dentro do prefixo/home |
| `SHELL` / `TERM` / `LANG` / `PWD` | `/system/bin/sh`, `xterm-256color`, `en_US.UTF-8`, projeto |

O `runtime` no terminal e o diagnóstico em *Plugins → Node.js* mostram
`OPENSSL_CONF` e `SSL_CERT_FILE` efetivos e se os arquivos existem.

### 1.2 Remoção do simulador de apt/dpkg — **feito**

Removidos por completo:

- `TermuxPackages.kt` — lia o índice apt do Termux, resolvia dependências e
  extraía `.deb` em Kotlin;
- `Archives.kt` — leitor de `.deb` (ar + `data.tar.xz`/`.gz`) usado por ele;
- a dependência `org.tukaani:xz` do Gradle;
- a opção *"Instalar do Termux"* e o método `installTermuxNode()`.

O app **não** simula `apt`/`dpkg`. O foco é rodar projetos Node.js
(backend/frontend) e `npm install`; o npm gerencia os pacotes JS locais em
`node_modules`. O runtime entra por:

1. **APK** — `jniLibs/<abi>/libnode.so` (executável ELF) ou `assets/node/`
   (`.zip`/`.tar.gz`/`node`), extraído no bootstrap;
2. **Importar** — *Plugins → Node.js → Importar executável* (`.zip`, `.tar.gz`,
   `node` Bionic);
3. **URL** — *Plugins → Node.js → Baixar de uma URL*.

A validação ELF (`PT_INTERP` Bionic × glibc) e o ajuste de `RUNPATH`
(`ElfPatcher` → `$ORIGIN/../lib`) continuam, agora aplicados também às `.so`
importadas junto com o binário.

### 1.3 Terminal: espaçamento, seleção/cópia e diretório — **feito**

- **Seleção e cópia nativas:** `TerminalView` deixou de ser uma `View` desenhada
  em `Canvas` e virou um `ScrollView` com um `TextView` monoespaçado e
  `setTextIsSelectable(true)`. Toque longo seleciona texto e a barra flutuante do
  Android copia/seleciona tudo, igual a qualquer terminal real. Há também o
  atalho `⧉ Copiar` na barra de teclas (copia a seleção ou, sem seleção, todo o
  buffer). O teclado do sistema continua digitando direto na tela via
  `InputConnection`.
- **Espaçamento alinhado:** cada linha renderizada tem exatamente `columns`
  células; o `TextView` usa fonte monoespaçada, sem ligaduras
  (`'liga' 0, 'calt' 0`), sem wrap duplo (`setHorizontallyScrolling(true)`, já que
  o emulator quebra em `columns`). Antes de cada comando o shell reexporta
  `COLUMNS`/`LINES` com o tamanho atual da tela, então o `ls` alinha as colunas
  com a área visível (correção do `ls` desalinhado).
- **Diretório sempre no projeto:** o `ProcessBuilder` do shell, do `run()` e do
  agente nasce com `.directory(raizDoProjeto)`. `Executar` e `npm install` chamam
  `ensureDirectory(projeto)` antes de rodar. O `cd` manual continua sendo
  acompanhado pelo marcador de `cwd`, mas os atalhos do app forçam a volta.
- **Renderização eficiente:** o scrollback do `TerminalEmulator` passou a ter
  acesso O(1) (`ArrayDeque` do Kotlin em vez de varredura linear), e a
  reconstrução do texto é adiada enquanto há seleção ativa (para não destruí-la).

---

## 2. O que foi verificado nesta sessão (com evidência)

O ambiente não tem Android SDK nem os artefatos AndroidX/Material/JGit, então
**o APK completo não foi compilado aqui**. Para não entregar código "no escuro":

1. **Compilação Kotlin real do núcleo** (`NodeRuntime`, `ElfPatcher`,
   `TerminalEmulator`, `TerminalView`, `TerminalSession`) contra `android.jar`
   (API 28) com o compilador Kotlin 2.1.0 e JDK 17: **sem erros**.
2. **Teste de runtime em JVM** (com `Context`/`Assets` falsos apontando para os
   assets reais do repo): `prepare()` cria `etc/tls` e extrai `openssl.cnf` +
   `cert.pem` sem placeholders e sem referência a `com.termux`; `environment()`
   injeta as 5 variáveis obrigatórias com o prefixo do app; `processBuilder()`
   aplica `directory()` = projeto e o ambiente TLS; um `openssl.cnf` com prefixo
   do Termux é reescrito; `run()` propaga `OPENSSL_CONF` ao processo filho.
   **Todos passaram.**
3. **Teste do emulador em JVM:** grade de `columns` células, `\r` de barra de
   progresso, ANSI SGR sem lixo, scrollback O(1) e tabulação em múltiplos de 8.
   **Todos passaram.**
4. **Configuração OpenSSL validada** com o `openssl` do host: o `openssl.cnf`
   gerado carrega o provider `default` e o `cert.pem` contém 118 certificados
   válidos.
5. Os erros de compilação restantes nos `Fragment`/`Activity` são **todos** de
   dependências externas ausentes no sandbox (androidx, material, jgit, `R`);
   nenhuma das APIs novas aparece como não resolvida.

O que isso **não** prova: que o APK monta com o Gradle completo (faltam
AndroidX/Material no sandbox) nem que o `node` do Termux executa no seu aparelho.
Valide no dispositivo com o roteiro abaixo.

---

## 3. Como validar no aparelho

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Abra um projeto → **⌘ Terminal**. O banner mostra `PREFIX` em `files/usr`.
2. `runtime` → confira `OPENSSL_CONF` e `SSL_CERT_FILE` apontando para
   `files/usr/etc/tls/…` com `(ok)`, e o interpretador ELF `/system/bin/linker64`.
3. `node -v` e `npm -v` → devem responder **sem** "Permission denied" do OpenSSL.
4. `ls` → colunas alinhadas com a largura do terminal.
5. **Toque longo** no texto → seleciona; a barra flutuante copia. Ou use
   `⧉ Copiar`.
6. `npm install` num projeto Express → instala em `node_modules` sem erro de TLS.
7. **▶ Executar** com `server.js` → o servidor Express sobe; **■ Parar** encerra
   o shell e o `node` filho.
8. Se algo falhar: `runtime` + `logcat -s NodeSphere`.

---

## 4. Limites honestos

- **Sem PTY nativo:** continua sendo um shell real em pipes com emulação de
  terminal do lado do app. Não há `SIGINT` real (`^C` reabre o shell), nem
  programas de tela cheia (`vim`, `htop`) ou `readline` avançado. Um PTY exigiria
  NDK/JNI, que este projeto não compila.
- **`COLUMNS`/`LINES`:** sem `SIGWINCH`, o tamanho é reexportado antes de cada
  comando; programas que leem o tamanho só na inicialização usam o valor antigo
  até o próximo comando.
- **Binário do Node não vem no APK do repositório:** é preciso importar um `node`
  Bionic ou empacotá-lo em `assets/node/` / `jniLibs/`. Não há download de `.deb`.
- **`targetSdk = 28`** mantido por causa da regra W^X (execução de binários em
  `files/`), como no Termux.

---

## 5. Arquivos tocados nesta rodada

```
app/src/main/java/com/mobile/nodeide/
  NodeRuntime.kt      (alt)  processBuilder único, prepare() TLS, ambiente OpenSSL
  TerminalView.kt     (alt)  ScrollView + TextView selecionável, cópia nativa
  TerminalEmulator.kt (alt)  scrollback O(1)
  TerminalSession.kt  (alt)  usa processBuilder, reexporta COLUMNS/LINES, help
  AgentFragment.kt    (alt)  usa processBuilder no projeto
  IdeFragment.kt      (alt)  atalho ⧉ Copiar, texto de oferta do Node
  PluginsFragment.kt  (alt)  remove "Instalar do Termux", foco em importar/npm
  TermuxPackages.kt   (removido)  simulador de apt/dpkg
  Archives.kt         (removido)  leitor de .deb
app/src/main/assets/runtime/   (novo)  openssl.cnf + cert.pem + README
app/src/main/assets/node/README.md, app/src/main/jniLibs/README.md  (alt)
app/src/main/res/layout/fragment_ide.xml  (alt)  comentário do terminal
app/build.gradle.kts  (alt)  remove org.tukaani:xz
README.md             (alt)
```
