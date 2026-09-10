package com.tianlin.aiarena

/** Read-only readiness probes deliberately fail closed when a vendor changes its upload UI. */
internal object ArenaAttachmentScript {
    private const val doubaoAreaSelector = "[data-testid=attachment_area],.guidance-input-surface > .relative > .container-tJHWhP"
    fun prepare(requestId: String, files: List<ArenaAttachment>, service: ArenaService): String {
        val expected = files.joinToString(",", "[", "]") { "{name:${ArenaJs.quote(it.name)},size:${it.sizeBytes},mime:${ArenaJs.quote(it.mimeType)}}" }
        return """
            (() => {
              const previous = window.__arenaAttachment;
              $reactHelpers
              ${if (service == ArenaService.DEEPSEEK) deepSeekIdentityHelpers else ""}
              if (previous && previous.listener) document.removeEventListener('change', previous.listener, true);
              if (previous && previous.selectionInput && previous.listener) previous.selectionInput.removeEventListener('change', previous.listener, true);
              if (previous && previous.pendingControl?.clickTarget) previous.pendingControl.clickTarget.removeEventListener('click', previous.pendingControl.clickListener, true);
              if (previous && previous.observer) previous.observer.disconnect();
              const shown=e=>{const r=e.getBoundingClientRect();return r.width>2&&r.height>2&&getComputedStyle(e).visibility!=='hidden'};
              const existing=Array.from(document.querySelectorAll(${ArenaJs.quote(when(service) {
                ArenaService.DEEPSEEK -> "._77cefa5 ._25c7358,._77cefa5 .d5fa3d1b"
                ArenaService.KIMI -> "[data-testid=input-attachment-list] .file-card-container,[data-testid=input-attachment-list] .image-thumbnail,.chat-editor-attachment-area .file-card-container,.chat-editor-attachment-area .image-thumbnail"
                ArenaService.DOUBAO -> "[data-testid=attachment_area] [data-testid^=attachment_],.guidance-input-surface > .relative > .container-tJHWhP"
                else -> "[data-arena-no-attachment-support]"
              })})).filter(shown);
              document.querySelectorAll(${ArenaJs.quote(doubaoAreaSelector)}).forEach(area=>{const props=reactProps(area,'attachmentStates');if(props&&props.attachmentStates.length)existing.push(area);});
              if(existing.length) return JSON.stringify({error:'原网页还有未发送的附件，请先到原网页移除后重试，避免重复上传'});
              const previousLocalIds=new Set(Array.from(document.querySelectorAll('._77cefa5 ._25c7358,._77cefa5 .d5fa3d1b')).map(card=>reactProps(card,'file')?.file?.localId).filter(id=>typeof id==='string'&&id));
              const state = {id:${ArenaJs.quote(requestId)},expected:$expected,chosen:false,selectionConfirmed:false,selectionInput:null,clicked:new WeakSet(),menuAttempts:new Map(),fileControlAttempt:null,controlSequence:0,pendingControl:null,baseline:new Set(document.querySelectorAll('*')),imageWasLoading:new WeakSet(),deepSeekLocalIds:[],deepSeekCandidates:{},previousLocalIds};
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
                ${if (service == ArenaService.KIMI) "if(e.target!==state.selectionInput)return;" else ""}
                const actual = Array.from(e.target.files || []);
                state.chosen = actual.length === state.expected.length && state.expected.every(f => actual.some(a => a.name === f.name && a.size === f.size));
                state.selectionConfirmed = state.chosen;
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
          const isCurrent=f=>{
            if(!f)return false;
            let root=f,depth=0;const seen=new Set();
            while(root.return){if(seen.has(root)||depth++>=512)return false;seen.add(root);root=root.return;}
            return root.tag===3&&root.stateNode&&root.stateNode.current===root;
          };
          if(isCurrent(fiber))return fiber;
          if(fiber&&isCurrent(fiber.alternate))return fiber.alternate;
          return null;
        };
        const reactProps=(element,key)=>{let fiber=currentFiber(element);for(let depth=0;fiber&&depth<15;depth++,fiber=fiber.return){if(fiber.memoizedProps&&fiber.memoizedProps[key])return fiber.memoizedProps;}return null;};
    """.trimIndent()

    // Prefer binding original metadata before DeepSeek's WebP conversion; readiness also
    // handles batched conversion after exact chooser confirmation and stable unique matching.
    private val deepSeekIdentityHelpers = """
        const rememberDeepSeekFiles=state=>{
          if(!state.chosen)return;
          const reserved=new Set(state.deepSeekLocalIds.filter(Boolean));
          document.querySelectorAll('._77cefa5 ._25c7358,._77cefa5 .d5fa3d1b').forEach(card=>{
            if(state.baseline.has(card))return;
            const props=reactProps(card,'file'),file=props&&props.file;
            if(!file||typeof file.localId!=='string'||!file.localId||reserved.has(file.localId)||state.previousLocalIds.has(file.localId))return;
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
          let doubaoMenuTrigger=null,doubaoMenuTarget=null,kimiFileLabel=null,kimiFileInput=null,kimiFileTrigger=null;
          ${when(service) {
              ArenaService.DEEPSEEK -> "const input=Array.from(document.querySelectorAll('textarea')).find(visible);const scope=input&&input.closest('._77cefa5');if(!scope)return JSON.stringify({waiting:true});"
              ArenaService.DOUBAO -> """
                const surfaces=Array.from(document.querySelectorAll('.guidance-input-surface')).filter(e=>visible(e)&&e.querySelector('textarea,[contenteditable=true]')&&e.querySelector('input[type=file]'));
                const scope=surfaces.length===1?surfaces[0]:document;
                if(surfaces.length===1){
                  const triggers=Array.from(scope.querySelectorAll('.guidance-input-actions button[data-slot=dropdown-menu-trigger][aria-haspopup=menu]')).filter(e=>visible(e)&&e.id&&e.querySelector('button[data-dbx-name=button][aria-haspopup=menu]'));
                  if(triggers.length===1){
                    const trigger=triggers[0];
                    const inner=Array.from(trigger.querySelectorAll('button[data-dbx-name=button][aria-haspopup=menu]'));
                    if(inner.length===1){
                      doubaoMenuTrigger=trigger;doubaoMenuTarget=inner[0];
                      const menus=Array.from(document.querySelectorAll('[role=menu][data-slot=dropdown-menu-content]')).filter(e=>visible(e)&&(e.getAttribute('aria-labelledby')||'').split(/\s+/).includes(trigger.id));
                      if(menus.length===1){
                        const local=Array.from(menus[0].querySelectorAll('[role=menuitem][data-slot=dropdown-menu-item]')).filter(e=>e.closest('[role=menu]')===menus[0]&&(e.textContent||'').replace(/\s+/g,'')==='上传文件或图片');
                        if(local.length===1)candidates.push(local[0]);
                      }else if(menus.length===0)candidates.push(doubaoMenuTarget);
                    }
                  }
                }else if(surfaces.length===0){
                  document.querySelectorAll('[data-testid=upload_file_button]').forEach(e=>candidates.push(e));
                }
              """.trimIndent()
              ArenaService.KIMI -> """
                const trigger=document.querySelector('.toolkit-trigger-btn');const scope=document;
                if(trigger&&trigger.id&&trigger.getAttribute('aria-haspopup')==='menu'){
                  const controls=(trigger.getAttribute('aria-controls')||'').split(/\s+/);
                  const menus=Array.from(document.querySelectorAll('[role=menu]')).filter(menu=>visible(menu)&&(controls.includes(menu.id)||(menu.getAttribute('aria-labelledby')||'').split(/\s+/).includes(trigger.id)));
                  if(trigger.getAttribute('aria-expanded')==='true'&&menus.length===1){
                    const labels=Array.from(menus[0].querySelectorAll('label.toolkit-item[role=menuitem]')).filter(label=>visible(label)&&label.closest('[role=menu]')===menus[0]&&label.querySelectorAll('input[type=file]').length===1);
                    if(labels.length===1){
                      const label=labels[0],input=label.querySelector('input[type=file]'),previous=state.fileControlAttempt;
                      if((!state.selectionInput||state.selectionInput===input)&&(!previous||(previous.label===label&&previous.input===input&&previous.triggerId===trigger.id))){
                        if(!state.chosen&&input.files&&input.files.length===0&&!input.disabled){kimiFileLabel=label;kimiFileInput=input;kimiFileTrigger=trigger;candidates.push(label);}
                      }
                    }
                  }else if(!state.fileControlAttempt&&trigger.getAttribute('aria-expanded')==='false'&&menus.length===0)candidates.push(trigger);
                }
              """.trimIndent()
              else -> "return JSON.stringify({error:'该成员暂不支持附件'});const scope=document;"
          }}
          scope.querySelectorAll('input[type=file]').forEach(input => {
            ${if(service == ArenaService.KIMI) "return; // Kimi local input is only eligible through the unique bound open menu above." else ""}
            ${if(service == ArenaService.DOUBAO) "if(!input.matches('[data-testid=upload-file-input]')&&!input.closest('[data-testid=upload_file_button]'))return;" else ""}
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
          const kimiTrigger=e=>${if (service == ArenaService.KIMI) "e.matches('.toolkit-trigger-btn')&&e.id&&e.getAttribute('aria-haspopup')==='menu'" else "false"};
          const menuTrigger=e=>kimiTrigger(e)?e:(e===doubaoMenuTarget?doubaoMenuTrigger:null);
          const closedMenu=e=>{
            const trigger=menuTrigger(e);
            if(!trigger||state.chosen||trigger.getAttribute('aria-expanded')!=='false')return false;
            if(kimiTrigger(e)&&document.querySelector('label.toolkit-item[role=menuitem] input[type=file]'))return false;
            const controls=(trigger.getAttribute('aria-controls')||'').split(/\s+/);
            return !Array.from(document.querySelectorAll('[role=menu]')).some(menu=>visible(menu)&&(controls.includes(menu.id)||(menu.getAttribute('aria-labelledby')||'').split(/\s+/).includes(trigger.id)));
          };
          const eligible=candidates.filter(e=>visible(e)&&getComputedStyle(e).pointerEvents!=='none'&&!e.disabled&&e.getAttribute('aria-disabled')!=='true'&&!['','true'].includes(e.getAttribute('data-disabled'))&&!e.classList.contains('disabled'));
          const target=eligible.find(e=>{
            if(e===kimiFileLabel){const attempt=state.fileControlAttempt;return !attempt||(attempt.count<3&&Date.now()-attempt.at>=2000);}
            const trigger=menuTrigger(e),attempt=trigger&&state.menuAttempts.get(trigger.id);
            if(!state.clicked.has(e)&&!attempt)return true;
            return attempt&&attempt.count<3&&Date.now()-attempt.at>=2000&&closedMenu(e);
          });
          if(!target){
            if(kimiFileLabel&&eligible.includes(kimiFileLabel)&&state.fileControlAttempt?.count>=3&&Date.now()-state.fileControlAttempt.at>=2000)return JSON.stringify({error:'Kimi 本地附件入口连续 3 次未响应，未发送问题；请打开原网页检查后重试'});
            if(eligible.some(e=>{const trigger=menuTrigger(e),attempt=trigger&&state.menuAttempts.get(trigger.id);return attempt&&attempt.count>=3&&Date.now()-attempt.at>=2000&&closedMenu(e);}))return JSON.stringify({error:${ArenaJs.quote(service.displayName + " 附件菜单连续 3 次未响应，未发送问题；请打开原网页检查后重试")}});
            return JSON.stringify({waiting:true});
          }
          target.scrollIntoView({block:'nearest',inline:'nearest'});
          const r=target.getBoundingClientRect(); const x=r.left+r.width/2,y=r.top+r.height/2;
          if(x<0||y<0||x>innerWidth||y>innerHeight) return JSON.stringify({waiting:true});
          const hit=document.elementFromPoint(x,y);
          if(!hit || !(target===hit || target.contains(hit) || hit.contains(target))) return JSON.stringify({waiting:true});
          const clickedTrigger=menuTrigger(target);
          if(state.pendingControl?.clickTarget)state.pendingControl.clickTarget.removeEventListener('click',state.pendingControl.clickListener,true);
          const hold={id:++state.controlSequence,target,wasClicked:state.clicked.has(target),menuId:clickedTrigger?.id,menuAttempt:clickedTrigger?state.menuAttempts.get(clickedTrigger.id):null,isFileTarget:target===kimiFileLabel,fileAttempt:state.fileControlAttempt,clickComplete:false,clickTarget:kimiFileInput||target};
          hold.clickListener=event=>{
            if(window.__arenaAttachment!==state||state.pendingControl!==hold)return;
            if(kimiFileInput&&event.target!==kimiFileInput)return;
            // A capture event precedes the browser's default activation. A later task acknowledges its completion.
            setTimeout(()=>{if(window.__arenaAttachment===state&&state.pendingControl===hold)hold.clickComplete=true;},0);
          };
          hold.clickTarget.addEventListener('click',hold.clickListener,true);
          state.pendingControl=hold;
          state.clicked.add(target);
          if(target===kimiFileLabel){
            // Kimi closes and unmounts the menu on input click, before chooser change can bubble to document.
            if(!state.selectionInput){state.selectionInput=kimiFileInput;kimiFileInput.addEventListener('change',state.listener,true);}
            const previous=state.fileControlAttempt;state.fileControlAttempt={label:target,input:kimiFileInput,triggerId:kimiFileTrigger.id,count:(previous?.count||0)+1,at:Date.now()};
          }
          if(clickedTrigger){const previous=state.menuAttempts.get(clickedTrigger.id);state.menuAttempts.set(clickedTrigger.id,{count:(previous?.count||0)+1,at:Date.now()});}
          return JSON.stringify({x,y,width:innerWidth,height:innerHeight,controlId:hold.id,retryableTap:!!clickedTrigger||target===kimiFileLabel});
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

    /** Only called while native has not dispatched DOWN for this exact control snapshot. */
    fun releaseUnsentControl(requestId: String, controlId: Long): String = """
        (()=>{
          const state=window.__arenaAttachment,hold=state&&state.pendingControl;
          if(!state||state.id!==${ArenaJs.quote(requestId)}||!hold||hold.id!==$controlId||state.chosen)return false;
          if(!hold.wasClicked)state.clicked.delete(hold.target);
          if(hold.menuId){if(hold.menuAttempt)state.menuAttempts.set(hold.menuId,hold.menuAttempt);else state.menuAttempts.delete(hold.menuId);}
          if(hold.isFileTarget){
            // A previous native chooser may arrive late. Keep this exact input bound until request cleanup.
            state.fileControlAttempt=hold.fileAttempt;
          }
          if(hold.clickTarget)hold.clickTarget.removeEventListener('click',hold.clickListener,true);
          state.pendingControl=null;
          return true;
        })();
    """.trimIndent()

    fun clickCompleted(requestId: String, controlId: Long): String = """
        (()=>{
          const state=window.__arenaAttachment,hold=state&&state.pendingControl;
          if(!state||state.id!==${ArenaJs.quote(requestId)}||!hold||hold.id!==$controlId||!hold.clickComplete)return false;
          hold.clickTarget.removeEventListener('click',hold.clickListener,true);
          return true;
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
            const allEntries=cards.map(card=>({card,props:reactProps(card,'file')})).filter(entry=>entry.props&&entry.props.file);
            const localIds=allEntries.map(({props})=>props.file.localId).filter(id=>typeof id==='string'&&id);
            if(cards.length>state.expected.length||localIds.some(id=>state.previousLocalIds.has(id))||new Set(localIds).size!==localIds.length)return JSON.stringify({error:'DeepSeek 出现额外、重复或旧附件身份，未发送问题'});
            const canConvert=expected=>expected.mime.startsWith('image/')&&(/\.(jpe?g|png|heic|heif)${'$'}/i.test(expected.name)||['image/jpeg','image/png','image/heic','image/heif'].includes(expected.mime));
            const webpName=expected=>{const dot=expected.name.lastIndexOf('.');return (dot>0?expected.name.slice(0,dot):expected.name)+'.webp';};
            let complete=0;const used=new Set();
            for(const [index,expected] of state.expected.entries()) {
              const localId=state.deepSeekLocalIds[index],isImage=expected.mime.startsWith('image/');
              const convertible=canConvert(expected),convertedName=webpName(expected);
              const entries=allEntries.filter(({card})=>!used.has(card));
              let deferredIdentity=false;
              let entry=entries.find(({props})=>{
                const file=props.file,exact=file.fileName===expected.name&&Number(file.fileSize)===expected.size;
                if(localId&&file.localId!==localId)return false;
                return exact||(convertible&&localId&&file.isImage===true&&file.fileName===convertedName&&Number(file.fileSize)>0);
              });
              if(!entry&&convertible&&!localId){
                const converted=entries.filter(({props})=>props.file.fileName===convertedName&&props.file.isImage===true);
                if(converted.length){
                  const originals=state.expected.filter(other=>other.name===convertedName||(canConvert(other)&&webpName(other)===convertedName));
                  if(!state.selectionConfirmed||originals.length!==1||converted.length!==1)return JSON.stringify({error:expected.name+'：DeepSeek 图片转换身份不唯一或选图未确认，未发送问题'});
                  entry=converted[0];deferredIdentity=true;
                }
              }
              if(!entry){
                if(allEntries.length===state.expected.length&&allEntries.every(({props})=>props.file.status==='SUCCESS'))return JSON.stringify({error:expected.name+'：DeepSeek 附件名称或身份不匹配，未发送问题'});
                continue;
              }
              const {card,props}=entry,file=props.file;used.add(card);
              if(card.querySelector('._6e3a316,.a1310f97,._8753412')) return JSON.stringify({error:expected.name+'：DeepSeek 附件解析失败'});
              if(['FAILED','ERROR','REJECTED','CONTENT_FILTER','CONTENT_TOO_LONG','CANCELLED','CONTENT_EMPTY','_CUSTOM_SYSTEM_ERROR_FAIL'].includes(file.status)||file.auditResult==='reject'||props.fileUploadInfo?.failed||props.fileErrorState?.hasError)return JSON.stringify({error:expected.name+'：DeepSeek 附件上传、解析或审核失败'});
              if(props.fileUploadInfo?.isUploading||props.isForking)continue;
              if(file.status!=='SUCCESS'||!file.id||file.id===file.localId||(isImage&&(file.isImage!==true||![null,'pass'].includes(file.auditResult??null))))continue;
              if(deferredIdentity){
                if(allEntries.length!==state.expected.length||localIds.length!==state.expected.length||!Number.isSafeInteger(Number(file.fileSize))||Number(file.fileSize)<=0)continue;
                const signature=JSON.stringify([file.localId,file.id,file.fileName,Number(file.fileSize)]),previous=state.deepSeekCandidates[index];
                if(previous&&previous.signature!==signature)return JSON.stringify({error:expected.name+'：DeepSeek 图片确认期间身份发生变化，未发送问题'});
                if(!previous){state.deepSeekCandidates[index]={signature,at:Date.now()};continue;}
                if(Date.now()-previous.at<500)continue;
                state.deepSeekLocalIds[index]=file.localId;
              }
              complete++;
            }
            return JSON.stringify({ready:complete===state.expected.length&&cards.length===state.expected.length,detail:'DeepSeek 已确认 '+complete+'/'+state.expected.length+' 个附件解析完成'});
          }
        """.trimIndent()
        ArenaService.DOUBAO -> """
          const areas=Array.from(document.querySelectorAll(${ArenaJs.quote(doubaoAreaSelector)}));
          const area=areas.length===1?areas[0]:null;
          if(area) {
            const props=reactProps(area,'attachmentStates'),files=props&&props.attachmentStates;
            if(!Array.isArray(files))return JSON.stringify({ready:false,detail:'无法确认豆包附件处理状态'});
            if(files.length>state.expected.length)return JSON.stringify({error:'豆包出现额外附件或恢复的旧草稿，未发送问题；请打开原网页检查未发送的附件'});
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
            if(cards.length>state.expected.length)return JSON.stringify({error:'Kimi 出现额外附件，未发送问题；请打开原网页检查未发送的附件'});
            let complete=0,imageIndex=0;
            for(const expected of state.expected) {
              let card;
              if(expected.mime.startsWith('image/')) {
                card=cards.filter(e=>e.matches('.image-thumbnail'))[imageIndex++];
              } else {
                const stem=expected.name.replace(/\.[^.]+${'$'}/,'');
                const ext=expected.name.split('.').pop().toLowerCase();
                const matches=cards.filter(e=>{
                  if(!e.matches('.file-card-container')||(e.querySelector('.file-card-info-name')?.textContent||'').trim()!==stem)return false;
                  const captions=Array.from(e.querySelectorAll('.file-ext')),icons=Array.from(e.querySelectorAll('img.file-card-icon'));
                  if(captions.length>1||icons.length>1)return false;
                  // FileCard always exposes meta.ext on its icon; FAILED replaces the detail row.
                  const extensions=[...captions.map(e=>e.textContent),...icons.map(e=>e.getAttribute('alt'))].map(value=>(value||'').trim().replace(/^\./,'').toLowerCase()).filter(Boolean);
                  return extensions.length>0&&extensions.every(value=>value===ext);
                });
                if(matches.length>1)return JSON.stringify({error:expected.name+'：Kimi 附件身份重复，未发送问题'});
                card=matches[0];
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
        (() => {const s=window.__arenaAttachment;if(s&&s.id===${ArenaJs.quote(requestId)}){document.removeEventListener('change',s.listener,true);if(s.selectionInput)s.selectionInput.removeEventListener('change',s.listener,true);if(s.pendingControl?.clickTarget)s.pendingControl.clickTarget.removeEventListener('click',s.pendingControl.clickListener,true);if(s.observer)s.observer.disconnect();delete window.__arenaAttachment;}return true;})();
    """.trimIndent()
}
