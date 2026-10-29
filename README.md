# NodeSphere IDE

Protótipo Android nativo escrito **100% em Kotlin**, com XML, Fragments e RecyclerView, inspirado em IDEs mobile. Usa `compileSdk = 36`, `targetSdk = 28` (ver [por que](#por-que-targetsdk-28)), `minSdk = 26` e o pacote `com.mobile.nodeide`.

## Linguagem: Kotlin

Todo o código-fonte é Kotlin; não há arquivos `.java` no projeto.

| Arquivo | Papel |
| --- | --- |
| `MainActivity.kt` | Activity única, navegação inferior, insets e *bootstrap* do runtime |
| `ProjectsFragment.kt` | Criação, importação (SAF) e clonagem de projetos |
| `IdeFragment.kt` | Editor: árvore, abas, símbolos, undo/redo e terminal integrado |
| `CodeEditor.kt` | `AppCompatEditText` com números de linha, realce lexical e autocompletar |
| `TerminalEmulator.kt` | Emulador de terminal em Kotlin puro: grade de caracteres, cursor, ANSI/SGR, scrollback |
| `TerminalView.kt` | `View` que desenha o terminal e recebe o teclado do sistema na própria tela |
| `TerminalSession.kt` | Shell `/system/bin/sh` na pasta do projeto: eco, edição de linha, histórico, `cwd`, `^C`/`^D` |
| `TermuxPackages.kt` | Instalador do Node.js **para Android** a partir dos pacotes do Termux (Bionic) |
| `Archives.kt` | Leitor de `.deb` (ar + `data.tar.xz`/`.gz`) e tar usado pelo instalador |
| `NodeRuntime.kt` | Prefixo `usr/`, ambiente do shell, validação ELF (Bionic × glibc) e npm |
| `AgentFragment.kt` | Chat compatível com OpenAI e ferramentas com aprovação |
| `PluginsFragment.kt` | Catálogo de integrações com ações reais de instalação |
| `PluginRows.kt` | Cartões de runtime com marcas, estados e ações |
| `ProjectRows.kt` | Cartões da lista de projetos |
| `ProjectScaffolder.kt` | Criação transacional dos templates de projeto |
| `SqliteConsoleDialog.kt` | Console SQL usando o SQLite nativo do Android |
| `Rows.kt` | `RecyclerView.Adapter` da árvore de arquivos |

Os arquivos ficam em `app/src/main/java/com/mobile/nodeide/`.

### Build

- Kotlin **2.1.0**, AGP **8.9.2**, Gradle **8.11.1** (wrapper), JDK **17**.
- `gradle.properties` já traz `android.useAndroidX=true` e `kotlin.code.style=official`.

```bash
./gradlew assembleDebug   # APK em app/build/outputs/apk/debug/
```

### Abrir no SmartIDE

1. Abra **esta pasta do projeto** no SmartIDE — não crie outro projeto *Empty Activity* por cima.
2. Deixe o SmartIDE sincronizar o Gradle (AGP 8.9.2, Kotlin 2.1.0, AndroidX).
3. Selecione o módulo `app` e execute. Se o SmartIDE exigir um JDK embutido, use **JDK 17**.

Se o SmartIDE gerou um template **Compose**, remova os arquivos do template em `app/src/main/kotlin/com/mobile/nodeide/` (incluindo a `MainActivity.kt` e `ui/theme/`), senão haverá declaração duplicada; este projeto usa XML.

| Copiar | Para (no projeto novo) |
| --- | --- |
| `app/src/main/java/com/mobile/nodeide/*.kt` | `app/src/main/java/com/mobile/nodeide/` |
| `app/src/main/res/layout/*.xml` | `app/src/main/res/layout/` (substituir `activity_main.xml`) |
| `app/src/main/res/values/themes.xml`, `strings.xml` | `app/src/main/res/values/` |
| `app/src/main/AndroidManifest.xml` | `app/src/main/` (manter `INTERNET` e `extractNativeLibs`) |

Dependências esperadas no `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment:1.8.6")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    implementation("org.slf4j:slf4j-nop:2.0.13")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.tukaani:xz:1.9")   // índices .xz e data.tar.xz dos .deb do Termux
}
```

`namespace`/`applicationId` = `com.mobile.nodeide`.

## Disponível

- **Editor com números de linha** desenhados na própria `Canvas` (coluna fixa, alinhada à fonte monoespaçada), realce lexical e autocompletar opcional. O realce agora compila cada expressão regular **uma única vez**, com atraso (*debounce*) e dentro de `try/catch`: nenhuma expressão inválida derruba mais o editor, e arquivos com mais de 60 mil caracteres não são realçados (para não travar a digitação).
- **Terminal interativo de verdade, sem caixa de texto separada**: um emulador próprio (`TerminalEmulator`) desenha a grade de caracteres, então o teclado do sistema digita **direto na tela do terminal** (toque = foco). Tem cursor piscando, cores ANSI/256/truecolor, `\r` de barras de progresso, apagamento/posicionamento de cursor, histórico rolável (arraste na tela) e eixo de teclas (`⇤ ← → ↑ ↓ Tab ^C ^D ⏎ clear | > ~ / -`). O shell começa **na pasta do projeto aberto**, com `PATH`, `HOME`, `TMPDIR` e `NODE_PATH` do prefixo, acompanha o `cd` e roda `ls`, `node` e `npm` no lugar certo — é o que faz o botão **Executar** funcionar. Prompt `nodesphere:projeto$`, `help`, `runtime`, `clear`, `^C` e `^D` são resolvidos pelo próprio app.
- **Runtime de Node.js/npm de verdade** em `files/usr` (layout parecido com o do Termux). O npm é baixado do `registry.npmjs.org` e extraído pelo próprio app; quando há Node e npm instalados, o terminal expõe `node`, `npm` e `npx`.
- Criação de projetos HTTP, API Express e Express fullstack em armazenamento privado; importação SAF e clonagem HTTPS com JGit.
- Catálogo de plugins com estado real (**INSTALADO / PRONTO PARA INSTALAR / DEPENDE DO NODE**), diagnóstico do runtime e console SQLite.
- Interface escura com edge-to-edge e insets; **a barra de navegação inferior desaparece quando um projeto está aberto** (o editor ocupa a tela inteira) e o botão Voltar retorna aos projetos.
- Chat com endpoint HTTPS **OpenAI-compatible** `/chat/completions`, histórico em memória e ferramentas de arquivos/comando, todas com aprovação explícita. O ambiente do prefixo também é aplicado aos comandos do agente.

## Node.js e npm

O Android usa **Bionic** como libc; o `node` publicado em nodejs.org é **glibc**. Por isso baixar o `.tar.gz` oficial termina em `No such file or directory` — o kernel procura o interpretador `/lib64/ld-linux-*.so` e ele não existe. O único `node` que executa em Android é o compilado contra Bionic, e é isso que o app instala agora.

1. **Instalar do Termux** (recomendado, sem PC): *Plugins → Node.js → Instalar do Termux*. O app lê o índice do repositório oficial do Termux (`https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-<arch>/Packages.xz`), resolve a árvore de dependências do pacote `nodejs` (`libc++`, `libandroid-support`, ...), baixa os `.deb`, extrai o `data.tar.xz` e copia o prefixo `data/data/com.termux/files/usr/...` para `files/usr/`. O `node` sai em `files/usr/bin/node` com permissão de execução aplicada pelo Kotlin (`setExecutable(true, false)`), e o npm que vem no pacote é reaproveitado (ou baixado do registry).

2. **Empacotado no APK** (offline, nada para o usuário fazer). Duas formas:

   ```bash
   # a) .deb do nodejs (mesmo pacote que o instalador usa), extraído na primeira abertura
   mkdir -p app/src/main/assets/node
   cp nodejs_*.aarch64.deb app/src/main/assets/node/

   # b) ou um executável de Node para Android (não glibc)
   mkdir -p app/src/main/jniLibs/arm64-v8a
   cp /caminho/para/node app/src/main/jniLibs/arm64-v8a/libnode.so
   ```

   `MainActivity.bootstrapRuntime()` tenta `jniLibs` e depois `assets/node/` (`installBundled()`/`installFromAssets()`).

3. **Importar** (*Plugins → Node.js → Importar executável*): aceita o `.deb` do Termux, um `node` de Android, `.zip` ou `.tar.gz`. O app valida o ELF de verdade: ABI **e interpretador** (`PT_INTERP` precisa apontar para `/system/bin/linker64`). Um binário glibc é recusado com explicação em vez de falhar depois com "No such file or directory".

4. **URL** (*Plugins → Node.js → Baixar de uma URL*) e **empacotado em `assets/node/`** também funcionam.

O npm pode ser atualizado em *Plugins → npm* (registry oficial, `latest` pelas dist-tags) e cria `files/usr/bin/npm`/`npx` executando `node npm-cli.js "$@"`. O comando `runtime` no terminal mostra ABI, versão, interpretador ELF, `PATH`, `HOME`, `TMPDIR` e `NODE_PATH` efetivos.

### Por que targetSdk 28

O Android 10+ bloqueia `execve` em arquivos gravados no diretório privado do app para apps com `targetSdk >= 29` (regra W^X). Como o Node é instalado em `files/usr/bin`, o app usa `targetSdk = 28` — a mesma escolha do Termux. `compileSdk` continua 36. Se você só usar o Node empacotado (`jniLibs`/`assets`), pode voltar para `targetSdk = 36`; nesse caso, porém, o Node baixado do Termux em `files/usr/bin` não poderá ser executado. Por isso `targetSdk = 28` continua fixo, como no Termux.

## Correções desta versão

- **Node.js compatível com Bionic/Android**: o downloader deixou de depender de binários glibc. O novo `TermuxPackages` instala o Node a partir do repositório oficial do Termux (índice apt → `.deb` → `data.tar.xz`), resolvendo as dependências (`libc++`, `libandroid-support`, ...) e mapeando `data/data/com.termux/files/usr/*` para `files/usr/*`. A extração em `files/usr/bin/node` termina com `setExecutable(true, false)` (e o mesmo para as `.so` do prefixo).
- **Validação de ELF com interpretador**: `installBinary` agora lê o `PT_INTERP`. Binário de Linux desktop (glibc) é recusado na hora com uma mensagem que explica o erro `No such file or directory` e indica o caminho certo; `libnode.so` (biblioteca, sem `main`) continua recusado.
- **Diretório de trabalho do terminal**: o `ProcessBuilder` do shell nasce com `.directory(pastaDoProjeto)` — antes ele abria em `files/home`, e por isso `ls` aparecia vazio. `Executar` e `npm install` chamam `ensureDirectory(projeto)` antes de rodar, então o comando sempre cai na pasta aberta, mesmo depois de um `cd`.
- **Ambiente do shell completo**: `PATH` = `files/usr/bin:<PATH do sistema>`, `HOME` = `files/home`, `TMPDIR` = `files/usr/tmp`, `NODE_PATH` = `files/usr/lib/node_modules`, mais `PREFIX`, `LD_LIBRARY_PATH`, `NPM_CONFIG_CACHE/PREFIX/TMP/USERCONFIG`, `SHELL`, `TERM=xterm-256color` e `PWD`.
- **Terminal PTY-style sem `EditText`**: saiu a caixa de texto isolada; entrou `TerminalView` + `TerminalEmulator` (grade de caracteres, ANSI, cursor, scrollback, teclado do sistema na própria tela) com `TerminalSession` fazendo eco local, edição de linha, histórico (`↑`/`↓`), `^C`/`^D` e fila de comandos.
- **targetSdk 28 mantido** por causa da regra W^X (execução de binários em `files/`).

## Limitações importantes (honestas)

- **Sem PTY nativo**: um pseudo-terminal exige `posix_openpt`/`grantpt`/`unlockpt` via NDK (bionic não tem `forkpty`), e este projeto não compila C++ — o sandbox não tem NDK. O terminal é, portanto, um **shell real em pipes com emulação de terminal do lado do app**: há eco, edição de linha, cores, cursor, histórico e rolagem, mas não há `SIGINT` real (o `^C` reabre o shell no mesmo diretório) nem programas de tela cheia (`vim`, `htop`) ou `readline` avançado. Programas que leem `stdin` sem parar podem engolir a próxima linha.
- **O APK do repositório não contém o binário do Node**: ele tem ~40–80 MB por ABI e só existe compilado para Bionic. A instalação é feita em tempo de execução (*Plugins → Node.js → Instalar do Termux*) ou empacotando `assets/node/*.deb` / `jniLibs/<abi>/libnode.so` antes do build.
- **`Packages.xz` do Termux precisa de rede**: o instalador fala com `packages.termux.dev` (e com o espelho `packages-cf.termux.dev`). Se a rede bloquear, a mensagem lista as URLs testadas e sugere importar o `.deb` manualmente.
- **Links simbólicos** dos pacotes são recriados com `java.nio.file.Files.createSymbolicLink` (API 26+); se o aparelho recusar, o link é ignorado com registro no log.
- **`COLUMNS`/`LINES`** são fixados no ambiente na abertura do shell: redimensionar o painel depois muda a emulação na tela, mas o processo filho não recebe `SIGWINCH` (não há tty).
- O realce é lexical genérico (não há parser completo de JS/TS/HTML/CSS/JSON/Markdown) e é limitado a 60 mil caracteres.
- MariaDB/MySQL exigem servidor externo e conector, ainda não integrados.
- A clonagem depende do JGit; se falhar em algum aparelho, use a importação por pasta.
- O agente usa apenas o protocolo OpenAI-compatible e não faz streaming de tokens. A chave fica nas preferências privadas sem criptografia; backup do app está desativado.

## Relatório técnico

O detalhamento desta rodada (o que foi corrigido, o que foi testado, o que ainda
não é possível e como validar no aparelho) está em
[`docs/RELATORIO-TERMINAL-E-NODE.md`](docs/RELATORIO-TERMINAL-E-NODE.md).
