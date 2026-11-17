# Mugen Char PickUp

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
3. Quando terminar, baixe o artefato **Mugen-Char-PickUp-apk** (é um .zip com o `.apk` dentro).
4. No celular, instale o APK (permita "instalar apps desconhecidos" para o app que você usou para abrir o arquivo).

Para publicar numa *Release*, crie uma tag `v0.2.0` (`git tag v0.2.0 && git push --tags`): o APK é anexado automaticamente.

## Atualizar o app por cima de uma versão antiga

Basta instalar o APK novo (da aba **Releases** ou do artefato do **Actions**): o Android mantém o app e as configurações. Isso funciona porque todo APK do projeto usa a mesma assinatura (`app/pickup.keystore`), o mesmo `applicationId` e um `versionCode` que sempre cresce (vem do número da execução no GitHub).

- **Uma vez só:** APKs anteriores à versão 0.2.0 foram assinados com chaves diferentes (a de debug muda a cada build). Desinstale a versão antiga uma vez antes de instalar a 0.2.0. Você não perde nada importante: o `select.def` e os personagens ficam na pasta do jogo, só o app pede a pasta de novo.
- A chave está no repositório de propósito, para qualquer pessoa conseguir compilar um APK que atualize o app. A consequência é que ela **não protege** contra um APK falso com a mesma assinatura. Para uso pessoal tudo bem; se um dia publicar o app para outras pessoas, gere uma chave privada e guarde-a nos *Secrets* do GitHub.

## Como usar

1. Abra o app e escolha a **pasta do Ikemen GO**, a mesma que contém `data/` e `chars/` (a que você escolheu quando o Ikemen GO pediu uma pasta).
2. O app descobre sozinho qual `select.def` o jogo usa e o tamanho do grid (`rows` e `columns` do `system.def`). Se o grid ficar diferente do jogo, ajuste em **Menu → Tamanho do grid**.
3. Use **Update char list** ou mexa no grid manualmente. Cada alteração é salva na hora no `select.def`.

### Tamanho do grid

**Menu → Tamanho do grid** grava `rows` e `columns` na seção `[Select Info]` do `system.def` do motif (por exemplo `data/ikemen1/system.def`), então o jogo passa a usar o mesmo tamanho. Só essas duas linhas são alteradas, e uma cópia fica em `system.def.bak` (feita antes da primeira alteração). Se o screenpack não tiver espaço para o novo tamanho, pode ser preciso ajustar `cell.size`/`pos` nele.

### Qual `select.def` é editado?

A wiki do Ikemen GO avisa que **no Android não se deve editar `data/select.def` diretamente**: vale a cópia dentro da pasta do motif (por exemplo `data/ikemen1/select.def`). O app segue essa regra: lê o motif em `save/config.json`, abre o `system.def` dele e usa o `select` definido em `[Files]`, procurando primeiro na pasta do motif e depois em `data/`.

## Limitações conhecidas

- **Pasta dentro de `Android/data/...` não funciona** em Android 11+ (o sistema bloqueia o acesso de outros apps). A pasta do jogo precisa estar em um lugar acessível (armazenamento interno, Documentos...).
- **Retratos**: cada célula mostra o *portrait* (sprite 9000,0 do `.sff`, o mesmo da tela de seleção do jogo). Lê SFF v1 e v2 (RLE8, RLE5, LZ5 e PNG). Se o personagem não tiver esse sprite, ou o `.sff` não puder ser lido, a célula mostra o nome. Os retratos são carregados só quando a célula aparece na tela.
- Posição vazia é gravada como a linha `empty`. Se o seu screenpack/versão do Ikemen GO tratar isso de outro jeito, o marcador fica numa constante só (`SelectDef.EMPTY_TOKEN`).
- Personagens com `exclude=1` não aparecem no grid e, ao salvar, são escritos depois das células do grid.
- Blocos `slot = { ... }` aparecem como uma célula só (nome do primeiro + `×N`) e se movem/removem em conjunto.
- Para arrastar: **segure** a célula até sentir a vibração e então arraste. Perto da borda de cima/baixo o grid rola sozinho.
- Os retratos foram verificados só com arquivos `.sff` montados nos testes, não com personagens reais de vários autores. Se algum aparecer como nome ou com cores erradas, me diga qual personagem.
- Comentários logo acima de uma entrada viajam com ela quando ela é movida.

## Estrutura

```
core/   Kotlin puro (sem Android): parser/escritor do select.def, regras do grid, localizador do select.def. Tem testes.
app/    App Android (Jetpack Compose): acesso às pastas (SAF), ViewModel e telas.
```

Compilar localmente: abra a pasta no Android Studio, ou `./gradlew :core:test :app:assembleDebug` (JDK 17 + Android SDK 34).

## Próximos passos sugeridos

1. Seleção de vários personagens de uma vez.
2. Cache dos retratos em disco (hoje são relidos a cada vez que o app abre).
3. Escolher entre vários `select.def` encontrados.
4. Editar parâmetros do personagem (stage, `order`, `music`...).
