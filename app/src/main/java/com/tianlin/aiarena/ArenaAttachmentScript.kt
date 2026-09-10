package com.tianlin.aiarena

/** Read-only readiness probes deliberately fail closed when a vendor changes its upload UI. */
internal object ArenaAttachmentScript {
    fun prepare(requestId: String, files: List<ArenaAttachment>, service: ArenaService): String {
        val expected = files.joinToString(",", "[", "]") { "{name:${ArenaJs.quote(it.name)},size:${it.sizeBytes},mime:${ArenaJs.quote(it.mimeType)}}" }
        return """
            (() => {
              const previous = window.__arenaAttachment;
              $reactHelpers
              ${if (service == ArenaService.DEEPSEEK) deepSeekIdentityHelpers else ""}
              if (previous && previous.listener) document.removeEventListener('change', previous.listener, true);
              if (previous && previous.observer) previous.observer.disconnect();
              const shown=e=>{const r=e.getBoundingClientRect();return r.width>2&&r.height>2&&getComputedStyle(e).visibility!=='hidden'};
              const existing=Array.from(document.querySelectorAll(${ArenaJs.quote(when(service) {
                ArenaService.DEEPSEEK -> "._77cefa5 ._25c7358,._77cefa5 .d5fa3d1b"
                ArenaService.KIMI -> "[data-testid=input-attachment-list] .file-card-container,[data-testid=input-attachment-list] .image-thumbnail,.chat-editor-attachment-area .file-card-container,.chat-editor-attachment-area .image-thumbnail"
                ArenaService.DOUBAO -> "[data-testid=attachment_area] [data-testid^=attachment_]"
                else -> "[data-arena-no-attachment-support]"
              })})).filter(shown);
              const doubaoArea=document.querySelector('[data-testid=attachment_area]');
              if(doubaoArea){const props=reactProps(doubaoArea,'attachmentStates');if(props&&props.attachmentStates.length)existing.push(doubaoArea);}
              if(existing.length) return JSON.stringify({error:'原网页还有未发送的附件，请先到原网页移除后重试，避免重复上传'});
              const state = {id:${ArenaJs.quote(requestId)},expected:$expected,chosen:false,clicked:new WeakSet(),baseline:new Set(document.querySelectorAll('*')),imageWasLoading:new WeakSet(),deepSeekLocalIds:[]};
              const rememberImages=()=>{
                document.querySelectorAll('[data-testid="input-attachment-list"] .image-thumbnail,.chat-editor-attachment-area .image-thumbnail').forEach(card=>{
                  if(!state.baseline.has(card)&&card.classList.contains('loading'))state.imageWasLoading.add(card);
                });
                ${if (service == ArenaService.DEEPSEEK) "rememberDeepSeekFiles(state);" else ""}
              };
              state.observer=new MutationObserver(rememberImages);
              state.observer.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class']});
              state.listener = e => {
                if (window.__arenaAttachment !== state || !e.target || e.target.type !== 'file') return;
                const actual = Array.from(e.target.files || []);
                state.chosen = actual.length === state.expected.length && state.expected.every(f => actual.some(a => a.name === f.name && a.size === f.size));
                if (!state.chosen) state.error = '网页收到的附件与所选附件不一致';
              };
              document.addEventListener('change', state.listener, true);
              window.__arenaAttachment = state;
              return true;
            })();
        """.trimIndent()
    }

    private val helpers = """
        const visible = e => { if (!e) return false; const r=e.getBoundingClientRect(),s=getComputedStyle(e); return r.width>2&&r.height>2&&s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0'; };
        $reactHelpers
        const state = window.__arenaAttachment;
    """.trimIndent()

    private val reactHelpers: String get() = """
        const currentFiber = element => {
          let fiber=element[Object.keys(element).find(k=>k.startsWith('__reactFiber${'$'}'))];
          const isCurrent=f=>{if(!f)return false;let root=f,guard=0;while(root.return&&guard++<100)root=root.return;return root.tag===3&&root.stateNode&&root.stateNode.current===root;};
          if(isCurrent(fiber))return fiber;
          if(fiber&&isCurrent(fiber.alternate))return fiber.alternate;
          return null;
        };
        const reactProps=(element,key)=>{let fiber=currentFiber(element);for(let depth=0;fiber&&depth<15;depth++,fiber=fiber.return){if(fiber.memoizedProps&&fiber.memoizedProps[key])return fiber.memoizedProps;}return null;};
    """.trimIndent()

    // DeepSeek may asynchronously convert mobile PNG/JPEG files to WebP. Bind the original
    // committed name/size to its stable localId before accepting changed server metadata.
    private val deepSeekIdentityHelpers = """
        const rememberDeepSeekFiles=state=>{
          if(!state.chosen)return;
          const reserved=new Set(state.deepSeekLocalIds.filter(Boolean));
          document.querySelectorAll('._77cefa5 ._25c7358,._77cefa5 .d5fa3d1b').forEach(card=>{
            if(state.baseline.has(card))return;
            const props=reactProps(card,'file'),file=props&&props.file;
            if(!file||typeof file.localId!=='string'||!file.localId||reserved.has(file.localId))return;
            const index=state.expected.findIndex((expected,i)=>!state.deepSeekLocalIds[i]&&expected.mime.startsWith('image/')&&file.fileName===expected.name&&Number(file.fileSize)===expected.size);
            if(index>=0){state.deepSeekLocalIds[index]=file.localId;reserved.add(file.localId);}
          });
        };
    """.trimIndent()

    fun nextControl(requestId: String, service: ArenaService): String = """
        (() => {
          $helpers
          if (!state || state.id !== ${ArenaJs.quote(requestId)}) return JSON.stringify({error:'附件请求已取消'});
          const candidates = [];
          ${when(service) {
              ArenaService.DEEPSEEK -> "const input=Array.from(document.querySelectorAll('textarea')).find(visible);const scope=input&&input.closest('._77cefa5');if(!scope)return JSON.stringify({waiting:true});"
              ArenaService.DOUBAO -> "const trigger=document.querySelector('[data-testid=upload_file_button]');if(trigger)candidates.push(trigger);const scope=document;"
              ArenaService.KIMI -> "const trigger=document.querySelector('.toolkit-trigger-btn');if(trigger)candidates.push(trigger);const scope=document;"
              else -> "return JSON.stringify({error:'该成员暂不支持附件'});const scope=document;"
          }}
          scope.querySelectorAll('input[type=file]').forEach(input => {
            ${if(service == ArenaService.KIMI) "if(!input.closest('label.toolkit-item[role=menuitem]'))return;" else ""}
            ${if(service == ArenaService.DOUBAO) "if(!input.closest('[data-testid=upload_file_button],[role=menu],[role=menuitem],label'))return;" else ""}
            if (visible(input)) candidates.push(input);
            if(input.id) document.querySelectorAll('label').forEach(label => { if(label.htmlFor===input.id) candidates.push(label); });
            const parent=input.parentElement;
            if(parent && parent.matches('label,button,[role=button]')) candidates.push(parent);
            // DeepSeek keeps a hidden input immediately after its visible upload control.
            if(input.previousElementSibling) {
              const sibling=input.previousElementSibling;
              if(sibling.matches('button,[role=button]')) candidates.push(sibling);
              else sibling.querySelectorAll('button,[role=button]').forEach(e=>candidates.push(e));
            }
          });
          const target=candidates.find(e=>visible(e)&&getComputedStyle(e).pointerEvents!=='none'&&!e.disabled&&e.getAttribute('aria-disabled')!=='true'&&!state.clicked.has(e));
          if(!target) return JSON.stringify({waiting:true});
          target.scrollIntoView({block:'nearest',inline:'nearest'});
          const r=target.getBoundingClientRect(); const x=r.left+r.width/2,y=r.top+r.height/2;
          if(x<0||y<0||x>innerWidth||y>innerHeight) return JSON.stringify({waiting:true});
          const hit=document.elementFromPoint(x,y);
          if(!hit || !(target===hit || target.contains(hit) || hit.contains(target))) return JSON.stringify({waiting:true});
          state.clicked.add(target);
          return JSON.stringify({x,y,width:innerWidth,height:innerHeight});
        })();
    """.trimIndent()

    fun readiness(requestId: String, service: ArenaService): String = """
        (() => {
          $helpers
          if(!state || state.id!==${ArenaJs.quote(requestId)}) return JSON.stringify({error:'附件请求已取消'});
          if(state.error) return JSON.stringify({error:state.error});
          if(!state.chosen) return JSON.stringify({ready:false,detail:'等待网页接收附件'});
          ${vendorReadiness(service)}
          return JSON.stringify({ready:false,detail:'当前网页附件结构未识别，未发送问题'});
        })();
    """.trimIndent()

    private fun vendorReadiness(service: ArenaService): String = when (service) {
        ArenaService.DEEPSEEK -> """
          $deepSeekIdentityHelpers
          rememberDeepSeekFiles(state);
          const input=Array.from(document.querySelectorAll('textarea')).find(visible);
          const composer=input&&input.closest('._77cefa5');
          const cards=composer?Array.from(composer.querySelectorAll('._25c7358,.d5fa3d1b')).filter(e=>visible(e)&&!state.baseline.has(e)):[];
          if(cards.length) {
            let complete=0;const used=new Set();
            for(const [index,expected] of state.expected.entries()) {
              const localId=state.deepSeekLocalIds[index],isImage=expected.mime.startsWith('image/');
              const convertible=isImage&&(/\.(jpe?g|png|heic|heif)${'$'}/i.test(expected.name)||['image/jpeg','image/png','image/heic','image/heif'].includes(expected.mime));
              const dot=expected.name.lastIndexOf('.'),convertedName=(dot>0?expected.name.slice(0,dot):expected.name)+'.webp';
              const entries=cards.filter(card=>!used.has(card)).map(card=>({card,props:reactProps(card,'file')})).filter(entry=>entry.props&&entry.props.file);
              const entry=entries.find(({props})=>{
                const file=props.file,exact=file.fileName===expected.name&&Number(file.fileSize)===expected.size;
                if(localId&&file.localId!==localId)return false;
                return exact||(convertible&&localId&&file.isImage===true&&file.fileName===convertedName&&Number(file.fileSize)>0);
              });
              if(!entry){
                if(convertible&&!localId&&entries.some(({props})=>props.file.fileName===convertedName&&props.file.status==='SUCCESS'))return JSON.stringify({error:expected.name+'：无法核对 DeepSeek 图片转换前的文件身份，请在原网页确认附件后手动发送'});
                continue;
              }
              const {card,props}=entry,file=props.file;used.add(card);
              if(card.querySelector('._6e3a316,.a1310f97,._8753412')) return JSON.stringify({error:expected.name+'：DeepSeek 附件解析失败'});
              if(['FAILED','ERROR','REJECTED','CONTENT_FILTER','CONTENT_TOO_LONG','CANCELLED','CONTENT_EMPTY','_CUSTOM_SYSTEM_ERROR_FAIL'].includes(file.status)||file.auditResult==='reject'||props.fileUploadInfo?.failed||props.fileErrorState?.hasError)return JSON.stringify({error:expected.name+'：DeepSeek 附件上传、解析或审核失败'});
              if(props.fileUploadInfo?.isUploading||props.isForking)continue;
              if(file.status==='SUCCESS'&&file.id&&file.id!==file.localId&&(!isImage||(file.isImage===true&&[null,'pass'].includes(file.auditResult??null))))complete++;
            }
            return JSON.stringify({ready:complete===state.expected.length&&cards.length===state.expected.length,detail:'DeepSeek 已确认 '+complete+'/'+state.expected.length+' 个附件解析完成'});
          }
        """.trimIndent()
        ArenaService.DOUBAO -> """
          const area=document.querySelector('[data-testid="attachment_area"]');
          if(area) {
            const props=reactProps(area,'attachmentStates'),files=props&&props.attachmentStates;
            if(!Array.isArray(files))return JSON.stringify({ready:false,detail:'无法确认豆包附件处理状态'});
            let complete=0;
            for(const expected of state.expected) {
              const file=files.find(f=>f.fileName===expected.name&&Number(f.size)===expected.size);
              if(!file)continue;
              if(file.status==='Retry'||file.parseState===2||file.reviewState===2)return JSON.stringify({error:expected.name+'：豆包附件上传、解析或审核失败'});
              if(file.status!=='Normal'||!file.fileKey||!file.localKey||![0,1].includes(file.reviewState??0))continue;
              if(expected.mime.startsWith('image/')) {
                // Normal + the matching remote key confirms upload; blobUrl remains the local preview.
                const remote=(file.imageList||[]).some(image=>image.key===file.fileKey);
                if(file.type==='image'&&remote)complete++;
              } else if(file.type==='file'&&file.parseState===1)complete++;
            }
            return JSON.stringify({ready:complete===state.expected.length&&files.length===state.expected.length,detail:'豆包已确认 '+complete+'/'+state.expected.length+' 个附件处理完成'});
          }
        """.trimIndent()
        ArenaService.KIMI -> """
          const area=document.querySelector('[data-testid="input-attachment-list"],.chat-editor-attachment-area');
          if(area) {
            const cards=Array.from(area.querySelectorAll('.file-card-container,.image-thumbnail')).filter(e=>visible(e)&&!state.baseline.has(e));
            let complete=0,imageIndex=0;
            for(const expected of state.expected) {
              let card;
              if(expected.mime.startsWith('image/')) {
                card=cards.filter(e=>e.matches('.image-thumbnail'))[imageIndex++];
              } else {
                const stem=expected.name.replace(/\.[^.]+${'$'}/,'');
                const ext=expected.name.split('.').pop().toLowerCase();
                card=cards.find(e=>e.matches('.file-card-container')&&(e.querySelector('.file-card-info-name')?.textContent||'').trim()===stem&&(e.querySelector('.file-ext')?.textContent||'').trim().replace(/^\./,'').toLowerCase()===ext);
              }
              if(!card) continue;
              if(card.classList.contains('error')) return JSON.stringify({error:expected.name+'：Kimi 附件上传或解析失败'});
              if(!card.classList.contains('success')) continue;
              if(expected.mime.startsWith('image/')) {
                const image=card.querySelector('img');
                const images=cards.filter(e=>e.matches('.image-thumbnail'));
                // Public Kimi upload pipeline: new PENDING/PROCESSING cards are loading;
                // only parse SUCCESS transitions them to success. Historical UNSPECIFIED is excluded.
                if(state.imageWasLoading.has(card) && image && /^https:\/\//.test(image.currentSrc||image.src||'') && images.length===state.expected.filter(f=>f.mime.startsWith('image/')).length) complete++;
              } else complete++;
            }
            return JSON.stringify({ready:complete===state.expected.length,detail:'Kimi 已确认 '+complete+'/'+state.expected.length+' 个附件解析完成'});
          }
        """.trimIndent()
        else -> ""
    }

    fun cancel(requestId: String): String = """
        (() => {const s=window.__arenaAttachment;if(s&&s.id===${ArenaJs.quote(requestId)}){document.removeEventListener('change',s.listener,true);if(s.observer)s.observer.disconnect();delete window.__arenaAttachment;}return true;})();
    """.trimIndent()
}
