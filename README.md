# NodeSphere IDE

Protótipo Android nativo escrito **100% em Kotlin**, com XML, Fragments e RecyclerView, inspirado em IDEs mobile. Usa `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26` e o pacote `com.mobile.nodeide`. É uma base funcional, **não uma implementação completa de todos os recursos de uma IDE**.

## Linguagem: Kotlin

Todo o código-fonte foi convertido de Java para Kotlin; não há mais arquivos `.java` no projeto.

| Arquivo | Papel |
| --- | --- |
| `MainActivity.kt` | Activity única (padrão *Empty Activity*), navegação inferior e acesso aos arquivos |
| `ProjectsFragment.kt` | Criação, importação (SAF) e clonagem de projetos |
| `IdeFragment.kt` | Editor: árvore, abas, símbolos, undo/redo e shell persistente |
| `CodeEditor.kt` | `AppCompatEditText` com realce lexical e autocompletar |
| `AgentFragment.kt` | Chat compatível com OpenAI e ferramentas com aprovação |
| `PluginsFragment.kt` | Catálogo de integrações Android e runtimes externos |
| `PluginRows.kt` | Cartões de runtime com marcas, estados e ações |
| `ProjectRows.kt` | Cartões da lista de projetos |
| `ProjectScaffolder.kt` | Criação transacional dos templates de projeto |
| `SqliteConsoleDialog.kt` | Console SQL usando o SQLite nativo do Android |
| `Rows.kt` | `RecyclerView.Adapter` da árvore de arquivos |

Os arquivos ficam em `app/src/main/java/com/mobile/nodeide/` — o diretório padrão do template Android (o AGP compila Kotlin nesse caminho, então a estrutura continua igual à do *Empty Activity*).

### Build

- Kotlin **2.1.0** (`org.jetbrains.kotlin.android`), AGP **8.9.2**, Gradle **8.11.1** (wrapper).
- JDK **17** (`compileOptions` e `jvmTarget` 17).
- `gradle.properties` já traz `android.useAndroidX=true` e `kotlin.code.style=official`.

```bash
./gradlew assembleDebug   # APK em app/build/outputs/apk/debug/
```

É necessário acesso aos repositórios Google/Maven e ao serviço de distribuição do Gradle (o wrapper baixa o Gradle 8.11.1 e o plugin Kotlin 2.1.0 na primeira sincronização).

### Abrir no SmartIDE

1. Abra **esta pasta do projeto** no SmartIDE — não crie outro projeto *Empty Activity* por cima, para não perder o código.
2. Deixe o SmartIDE sincronizar o Gradle; ele baixa AGP 8.9.2, Kotlin 2.1.0 e as dependências AndroidX.
3. Selecione o módulo `app` e execute. O projeto já segue o formato de um projeto *Empty Activity* (uma `MainActivity` + layouts XML em `res/layout`), só que com as telas extras em Fragments.
4. Se o SmartIDE exigir um JDK embutido, use **JDK 17** (as versões de Kotlin/AGP acima não funcionam com JDK 11 ou anterior).

### Se você criou um projeto *Empty Activity* novo no SmartIDE

Criar o projeto primeiro também funciona: copie os arquivos deste repositório para dentro dele, exatamente nos mesmos caminhos.

Se o SmartIDE gerou um template **Compose**, remova os arquivos do template que ficaram em `app/src/main/kotlin/com/mobile/nodeide/`, incluindo a `MainActivity.kt` e os arquivos de `ui/theme/`, desde que não contenham alterações suas que queira preservar. Eles não fazem parte deste projeto, que usa XML, e manter a `MainActivity` do template junto com a de `app/src/main/java/` causa uma declaração duplicada. Faça backup antes de remover quaisquer arquivos próprios. Não é necessário adicionar Compose para este projeto.

| Copiar | Para (no projeto novo) |
| --- | --- |
| `app/src/main/java/com/mobile/nodeide/*.kt` | `app/src/main/java/com/mobile/nodeide/` |
| `app/src/main/res/layout/*.xml` | `app/src/main/res/layout/` (substituir `activity_main.xml` do template) |
| `app/src/main/res/values/themes.xml`, `strings.xml` | `app/src/main/res/values/` |
| `app/src/main/AndroidManifest.xml` | `app/src/main/` (manter a linha de permissão `INTERNET`) |

No `app/build.gradle.kts` do projeto novo, o plugin `org.jetbrains.kotlin.android` já vem aplicado pelo template; basta garantir `jvmTarget` 17 e as dependências:

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
}
```

E, em `gradle.properties`, mantenha `android.useAndroidX=true` (sem isso o build falha por causa das dependências AndroidX). O `namespace` deve ser `com.mobile.nodeide` — igual ao `package` dos arquivos Kotlin.

## Disponível

- Criação de projetos HTTP, API Express e Express fullstack em armazenamento privado. Os arquivos são escritos em uma pasta temporária e só aparecem no workspace depois que o template termina; o `package.json` é gerado por `JSONObject`. Pesquisa, abertura, importação SAF (cópia local) e clonagem HTTPS de repositórios públicos com JGit.
- Catálogo visual de plugins com ícones de marca e estado explícito. Terminal Android e console SQLite têm ações funcionais; o console SQLite cria `nodesphere.sqlite` no projeto e executa consultas/comandos SQL.
- Interface escura redesenhada e compatível com edge-to-edge: os insets de status, navegação e teclado são aplicados ao conteúdo, sem desenhar títulos por trás das barras do sistema.
- Editor com árvore expansível, abas, criação e salvamento de arquivos, pesquisa, destaque lexical básico e barra de símbolos. Alterações são salvas ao trocar de arquivo ou sair da tela. O controle de autocompletar habilita sugestões básicas de palavras-chave, sem análise semântica. Undo/redo mantém até 100 estados do arquivo atual e é reiniciado ao trocar de arquivo.
- Painel redimensionável que executa comandos com `/system/bin/sh` no diretório do projeto, exibindo saída. O shell persiste enquanto o editor está aberto, permitindo `cd` e entrada de dados; não é um terminal PTY e programas que exigem controle de terminal podem não funcionar. O botão Parar encerra o shell, mas não garante encerrar processos filhos.
- Chat com endpoint HTTPS **OpenAI-compatible** `/chat/completions`, histórico em memória e ferramentas para listar, ler, gravar e excluir arquivos e executar comandos. Cada chamada exige aprovação do usuário; comandos são encerrados após 30 segundos. Os caminhos das ferramentas de arquivo são limitados ao projeto, mas comandos shell têm as permissões normais do aplicativo e não são confinados ao projeto. Use apenas provedores confiáveis; conteúdo do projeto e resultados das ferramentas são enviados ao provedor. A chave fica nas preferências privadas sem criptografia; backup do aplicativo está desativado.

## Limitações importantes

Node.js, npm, MariaDB e MySQL **não estão incluídos e ainda não podem ser instalados pelo catálogo**. O catálogo deixa isso explícito em vez de simular uma instalação. Node/npm requerem uma distribuição Android compatível com a ABI e o sandbox; MariaDB/MySQL requerem um servidor externo e um conector, ainda não integrados. Terminal Android e SQLite são nativos; o console SQLite trabalha com um arquivo dentro do projeto. Em aparelhos comuns, `node`, `npm` e `git` não existirão no shell. PTY, debugger e autocomplete semântico seguem como trabalho futuro. O realce é lexical genérico, não um parser completo para JS/TS/HTML/CSS/JSON/Markdown/EJS/Handlebars.

A clonagem depende do JGit, que requer APIs Java nem sempre disponíveis em Android; se a clonagem falhar em algum aparelho, use a importação por pasta.

O agente usa apenas o protocolo OpenAI-compatible; os endpoints predefinidos dependem de conta, modelo e suporte a tool calling do provedor. O protocolo nativo Anthropic/Claude não está implementado. Não há streaming de tokens: ações e resultados são apresentados conforme cada chamada termina. Não conceda aprovação a comandos que não compreenda.
