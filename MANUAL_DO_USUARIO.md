# Manual do Usuário — LabRoM Sex Classifier

Guia passo a passo para profissionais da área médica e forense.
**Nenhum conhecimento prévio de informática é necessário.**

---

## 1. O que é este programa?

É uma versão personalizada do **Weasis** (um visualizador gratuito de exames DICOM)
com um plugin desenvolvido pelo **LabRoM** que utiliza inteligência artificial
para auxiliar na **classificação de sexo** a partir de imagens de tomografia
computadorizada (CT) de crânio e pelve.

Para cada série carregada, o programa:

1. Identifica automaticamente o quadro mais informativo (chamado de *frame pivô*).
2. Classifica como **Masculino** ou **Feminino**, com a porcentagem de confiança.
3. Gera um **mapa de calor** (heatmap) mostrando *onde* a IA olhou para tomar a
   decisão — útil para conferência por um especialista.

> **Aviso importante:** esta ferramenta é um **apoio à decisão**. O resultado
> deve sempre ser revisado por um(a) profissional habilitado(a). Ela não
> substitui a avaliação humana especializada.

---

## 2. Requisitos do computador

| Item | Mínimo |
|---|---|
| Sistema operacional | Windows 10 ou Windows 11 (64 bits) |
| Memória RAM | 8 GB |
| Espaço em disco | ~5 GB livres |
| Internet | **Não é necessária** depois da instalação |

O programa é **autossuficiente**: ele já vem com tudo o que precisa por dentro
(Java, Python, modelos de IA). Você **não** precisa instalar nada à parte.

---

## 3. Como instalar

### Passo 1 — Receba o arquivo de instalação

Você vai receber um arquivo com extensão **`.msi`**, com nome parecido com:

```
Weasis-x.y.z.msi
```

Salve-o em algum local fácil de encontrar (por exemplo, na Área de Trabalho).

### Passo 2 — Clique duas vezes no arquivo

Vai abrir o instalador do Windows.

### Passo 3 — Aviso do Windows SmartScreen (se aparecer)

Como o programa ainda não é assinado digitalmente pela Microsoft, pode aparecer
uma tela azul dizendo *"O Windows protegeu o seu PC"*. Isso é normal.

- Clique em **"Mais informações"**.
- Em seguida clique em **"Executar assim mesmo"**.

> Se a sua equipe de TI bloquear a execução, peça que liberem o instalador.
> O programa **não acessa a internet**, **não envia dados** e **não coleta
> informações** sobre os pacientes.

### Passo 4 — Avance pelo instalador

Clique em **Next** (Próximo) algumas vezes e depois em **Install** (Instalar).
Pode ser que o Windows peça permissão de administrador — clique em **Sim**.

A instalação leva 1 a 2 minutos.

### Passo 5 — Instalação concluída

Vai aparecer um ícone chamado **Weasis** na Área de Trabalho e no menu Iniciar.

---

## 4. Primeira execução

Dê duplo clique no ícone **Weasis** na Área de Trabalho.

> **Importante:** *na primeira vez* que você abrir o programa, ele pode levar
> alguns segundos a mais (o Windows costuma verificar todos os componentes
> recém-instalados na primeira execução). Nas próximas aberturas, o programa
> abre normalmente em poucos segundos.

Quando terminar de carregar, a tela principal do Weasis vai aparecer.

> **Pré-carregamento da IA:** logo após abrir uma série DICOM, o programa
> começa a carregar os modelos de inteligência artificial **em segundo
> plano**. Você não precisa esperar — pode usar o Weasis normalmente. Esse
> pré-carregamento faz com que a sua primeira classificação seja praticamente
> instantânea.

---

## 5. Carregar um exame DICOM

Você pode carregar um exame de duas formas:

### Forma A — Arrastar e soltar

1. Abra a pasta onde estão os arquivos DICOM do paciente no Explorador de
   Arquivos do Windows.
2. Selecione todos os arquivos da série (Ctrl+A para selecionar todos).
3. Arraste-os para dentro da janela do Weasis.

### Forma B — Pelo menu

1. No Weasis, clique em **File** (Arquivo) no canto superior esquerdo.
2. Clique em **Open** (Abrir).
3. Navegue até a pasta do exame e selecione os arquivos DICOM.
4. Clique em **Abrir**.

As imagens da série vão aparecer no visualizador central. Você pode rolar a roda
do mouse para navegar pelos cortes.

---

## 6. Executar a classificação

Com a série DICOM aberta no visualizador:

1. Localize o botão **"Sex Classification"** na barra de ferramentas superior.
2. Clique nele **uma única vez**.
3. O botão muda para **"…"** indicando que o programa está processando.
4. Aguarde. O processamento leva de **alguns segundos a cerca de 1 minuto**,
   dependendo do tamanho da série.

Enquanto processa, no painel lateral aparecem mensagens informativas como:

- *Scanning DICOM images…* — lendo as imagens
- *Detecting pivot frame…* — escolhendo o melhor quadro
- *Running sex classification…* — fazendo a classificação
- *Building result images…* — montando o resultado

---

## 7. Interpretar os resultados

Quando o processamento termina, **dois lugares** mostram informação:

### 7.1 Painel lateral direito (cartões de resumo)

No painel à direita aparece um **cartão colorido** para cada classificação
feita na sessão. O cartão é compacto e mostra:

- **Miniatura** (à esquerda) — clique nela para reabrir a série de resultados
  no visualizador central.
- **Resultado final** (ex.: *Male Skull*, *Female Pelvis*).
- **Patient:** identificação do paciente lida do DICOM.
- **XX.X% confidence** — confiança geral da IA.
- Botão **"Export Heatmap"** — usado para salvar as imagens em disco
  (veja seção 9).

A cor de fundo do cartão também indica o resultado:
**azul escuro = masculino**, **vinho = feminino**.

### 7.2 Visualizador central (imagens compostas)

No visualizador central, em vez da série DICOM original, abrem-se as
**imagens compostas** geradas pela análise. Para cada quadro analisado o
programa monta uma imagem com três partes:

- **Barra superior:** repete a classificação geral (ex.:
  *General Classification: Male Skull   (92.3%)*).
- **Corpo, à esquerda:** o quadro original do exame.
- **Corpo, à direita:** o **mapa de calor** (heatmap) mostrando, em cores
  quentes (amarelo/vermelho), as regiões da imagem que mais influenciaram a
  decisão da IA.
- **Legenda inferior:** identificação por quadro, por exemplo:

  ```
  Frame 6: Male Skull   92.3%
  ```

  Mostrando o número do quadro, a classificação **daquele quadro específico**
  e a confiança individual.

Use a roda do mouse para navegar entre os quadros compostos exatamente como
faz com uma série DICOM normal.

### 7.3 Como avaliar o mapa de calor

Use o heatmap para conferir se a IA "olhou" para uma região anatomicamente
relevante. Se ela estiver olhando para fora do osso (por exemplo, para
artefatos ou para o fundo da imagem), o resultado provavelmente **não é
confiável**.

> **Como interpretar a confiança:**
> - Acima de **80%**: alta confiança.
> - Entre **50% e 80%**: confiança moderada — revise com atenção.
> - Abaixo de **50%**: baixa confiança — **não** use o resultado sem
>   verificação por especialista.

---

## 8. Carregar outro exame

Para classificar um novo paciente:

1. Feche a série atual (ou abra uma aba nova).
2. Carregue a nova série (seção 5).
3. Clique novamente em **Sex Classification**.

Os resultados anteriores ficam no painel lateral até você fechar o programa.

---

## 9. Salvar os resultados (Export Heatmap)

**Atenção:** os resultados **não são salvos automaticamente** em disco — se
você fechar o Weasis sem exportar, eles são perdidos. Para guardar o resultado
para prontuário ou laudo, use o botão **Export Heatmap** no cartão do
painel lateral direito.

### Como exportar

1. No painel lateral direito, localize o cartão da classificação que você
   quer salvar.
2. Clique no botão **"Export Heatmap"** dentro do cartão.
3. Vai abrir uma janela pedindo para escolher uma **pasta de destino**
   (selecione, por exemplo, uma pasta com o nome do caso).
4. Clique em **Save**.

### O que é salvo

O programa cria automaticamente, dentro da pasta que você escolheu, uma
estrutura organizada:

```
<pasta escolhida>/
  <classificação>/        ex.: Male Skull
    <ID do paciente>/     ex.: 12345678
      frame_0001.png      composta: original | heatmap
      frame_0002.png
      …
```

Cada arquivo `frame_NNNN.png` é a **imagem composta completa** (com cabeçalho,
quadro original, heatmap e legenda) — pronta para anexar a um laudo.

Ao terminar, uma janela confirma quantos arquivos foram exportados e o
caminho completo da pasta.

> Uma função de **exportação automática de relatório em PDF** está prevista
> para versões futuras.

---

## 10. Mensagens comuns e o que fazer

| Mensagem | O que significa | O que fazer |
|---|---|---|
| *No series loaded in the viewer.* | Você clicou no botão sem ter aberto um exame. | Carregue um exame DICOM antes (seção 5). |
| *Unsupported format.* | O arquivo aberto não é um DICOM válido. | Verifique se você selecionou os arquivos certos. |
| *No Secondary Capture (SC) found.* | O exame não contém imagens do tipo "Secondary Capture", que é o que o modelo precisa. | Confira com o radiologista se a série exportada inclui as imagens SC. |
| *AI service unavailable. Please restart Weasis.* | O componente de IA caiu durante o uso. | Feche o Weasis completamente e abra de novo. |
| *Pivot detection produced no images.* | A IA não conseguiu identificar nenhum quadro adequado para analisar. | Verifique a qualidade do exame; pode ser que a série esteja incompleta. |

---

## 11. Problemas comuns

### O programa demora muito para abrir

Na primeira execução o Windows costuma fazer uma varredura de segurança nos
componentes recém-instalados — isso pode adicionar alguns segundos. A partir
da segunda execução o programa abre rapidamente. Se continuar lento sempre,
peça ajuda da equipe técnica.

### O botão "Sex Classification" não aparece

- Confirme que você abriu o **Weasis** instalado (e não outra versão do Weasis).
- Tente fechar e abrir de novo.

### O computador trava ou fica muito lento durante a análise

A IA usa bastante memória RAM enquanto processa. Feche outros programas pesados
(navegadores com muitas abas, editores de imagem) antes de classificar.

### Como desinstalar

Vai em **Configurações do Windows → Aplicativos → Aplicativos instalados**,
procure por **Weasis**, clique nos três pontinhos e selecione **Desinstalar**.

---

## 12. Privacidade e segurança dos dados

- O programa **não envia nada pela internet**.
- As imagens analisadas **não saem do seu computador**.
- Não existe coleta de telemetria, estatísticas de uso ou identificação de
  pacientes.
- Os arquivos temporários gerados durante a análise são apagados
  automaticamente quando você fecha o Weasis.

---

## 13. Suporte

Em caso de dúvidas ou problemas, entre em contato com a equipe **LabRoM**.
Ao reportar um problema, por favor envie:

- Uma descrição do que estava fazendo no momento.
- A mensagem de erro (se houver) — pode tirar uma foto da tela.
- Se possível, o tipo de exame que estava analisando (sem dados do paciente).

---

*Versão do manual: maio de 2026.*
*Programa: LabRoM Sex Classifier (fork do Weasis DICOM Viewer).*
