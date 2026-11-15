# Ikemen Select Manager

App Android para gerenciar a seleção de personagens do **Ikemen GO** (e M.U.G.E.N) sem editar o `select.def` na mão.

- **Update char list**: compara a pasta `chars/` com o `select.def` e adiciona só os personagens que ainda não estão na lista (nas primeiras posições vazias do grid; se não houver, no fim).
- **Grid interativo** igual ao da tela de seleção do jogo: toque numa célula para selecionar, **segure e arraste** para mover (soltar em célula vazia move; em célula ocupada, troca).
- **Remover** o personagem selecionado: ele sai do `select.def` e a posição fica vazia (os outros não se mexem).
- **Adicionar manualmente** numa célula vazia: o app abre o seletor de arquivos já dentro de `chars/` e você escolhe o `.def`.
- **Desfazer** (até 50 passos) e **backup automático**: antes da primeira alteração de cada sessão o original é copiado para `select.def.bak`.
- Só mexe na seção `[Characters]`. O resto do arquivo (comentários, `[ExtraStages]`, `[Options]`...) é preservado, inclusive quebras de linha e acentos.

## Como obter o APK (sem instalar nada)

1. Crie um repositório no GitHub e envie este projeto.
2. Abra a aba **Actions**: o workflow *Build APK* roda sozinho a cada push (ou clique em *Run workflow*).
3. Quando terminar, baixe o artefato **IkemenSelectManager-debug** (é um .zip com o `.apk` dentro).
4. No celular, instale o APK (permita "instalar apps desconhecidos" para o app que você usou para abrir o arquivo).

Para publicar numa *Release*, crie uma tag `v0.1.0` (`git tag v0.1.0 && git push --tags`): o APK é anexado automaticamente.

## Como usar

1. Abra o app e escolha a **pasta do Ikemen GO**, a mesma que contém `data/` e `chars/` (a que você escolheu quando o Ikemen GO pediu uma pasta).
2. O app descobre sozinho qual `select.def` o jogo usa e o tamanho do grid (`rows` e `columns` do `system.def`). Se o grid ficar diferente do jogo, ajuste em **Menu → Tamanho do grid**.
3. Use **Update char list** ou mexa no grid manualmente. Cada alteração é salva na hora no `select.def`.

### Qual `select.def` é editado?

A wiki do Ikemen GO avisa que **no Android não se deve editar `data/select.def` diretamente**: vale a cópia dentro da pasta do motif (por exemplo `data/ikemen1/select.def`). O app segue essa regra: lê o motif em `save/config.json`, abre o `system.def` dele e usa o `select` definido em `[Files]`, procurando primeiro na pasta do motif e depois em `data/`.

## Limitações conhecidas

- **Pasta dentro de `Android/data/...` não funciona** em Android 11+ (o sistema bloqueia o acesso de outros apps). A pasta do jogo precisa estar em um lugar acessível (armazenamento interno, Documentos...).
- **Ainda não mostra os retratos** dos personagens: as células mostram o nome. Ler o retrato (sprite 9000,0 do `.sff`) é a próxima etapa.
- Posição vazia é gravada como a linha `empty`. Se o seu screenpack/versão do Ikemen GO tratar isso de outro jeito, o marcador fica numa constante só (`SelectDef.EMPTY_TOKEN`).
- Personagens com `exclude=1` não aparecem no grid e, ao salvar, são escritos depois das células do grid.
- Blocos `slot = { ... }` aparecem como uma célula só (nome do primeiro + `×N`) e se movem/removem em conjunto.
- Ao arrastar não há rolagem automática: para mover para longe, aumente o espaço ou mova em etapas.
- Comentários logo acima de uma entrada viajam com ela quando ela é movida.

## Estrutura

```
core/   Kotlin puro (sem Android): parser/escritor do select.def, regras do grid, localizador do select.def. Tem testes.
app/    App Android (Jetpack Compose): acesso às pastas (SAF), ViewModel e telas.
```

Compilar localmente: abra a pasta no Android Studio, ou `./gradlew :core:test :app:assembleDebug` (JDK 17 + Android SDK 34).

## Próximos passos sugeridos

1. Retratos reais no grid (leitor de `.sff` v1/v2).
2. Rolagem automática ao arrastar e seleção de vários personagens.
3. Escolher entre vários `select.def` encontrados.
4. Editar parâmetros do personagem (stage, `order`, `music`...).
