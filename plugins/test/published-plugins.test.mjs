import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,mkdtempSync,existsSync,rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {stagePublishedPlugins} from '../scripts/stage-published-plugins.mjs';
import {verifyPackage} from '../scripts/package-verification.mjs';
const bytes=readFileSync(new URL('../../app/src/test/resources/plugins/fixture-signed.mbplugin',import.meta.url));
const verified=verifyPackage(bytes),id=verified.manifest.id;
function fixture(){const dir=mkdtempSync(join(tmpdir(),'published-plugin-'));const row={id,name:verified.manifest.name,code:'007',sha256:verified.sha256,size:bytes.length,fingerprint:verified.fingerprint,version:verified.manifest.version,versionCode:verified.manifest.versionCode};return {dir,output:join(dir,'dist'),descriptor:{format:1,plugins:[row]},catalog:[{id,code:'007',url:'https://buzzheavier.com/abcdefgh1234'}],expectedAuthor:verified.fingerprint,expectedIds:[id]};}
test('compiled packages are staged only after their pinned identity and bytes verify',async()=>{
 const x=fixture();try{const rows=await stagePublishedPlugins({...x,readPackage:async url=>{assert.equal(url,x.catalog[0].url);return bytes;}});assert.equal(rows.length,1);assert.deepEqual(readFileSync(join(x.output,'fixture.mbplugin')),bytes);}finally{rmSync(x.dir,{recursive:true,force:true});}
});
test('wrong hash, author, metadata ID and code cannot be staged',async()=>{
 for(const kind of ['hash','author','id','code']){
  const x=fixture();try{const row=x.descriptor.plugins[0];if(kind==='hash')row.sha256='0'.repeat(64);if(kind==='author')row.fingerprint='0'.repeat(64);if(kind==='id')x.catalog[0].id='dev.wrong.plugin';if(kind==='code')row.code='999';await assert.rejects(stagePublishedPlugins({...x,readPackage:async()=>bytes}));assert.equal(existsSync(x.output),false);}finally{rmSync(x.dir,{recursive:true,force:true});}
 }
});
test('failed downloads cannot leave a partial release folder',async()=>{
 const x=fixture();try{await assert.rejects(stagePublishedPlugins({...x,readPackage:async()=>{throw new Error('offline');}}),/offline/);assert.equal(existsSync(x.output),false);}finally{rmSync(x.dir,{recursive:true,force:true});}
});

test('native promotion fixture tracks the exact published descriptor',()=>{
 const descriptor=JSON.parse(readFileSync(new URL('../published.json',import.meta.url)));
 const native=JSON.parse(readFileSync(new URL('../../app/src/androidTest/assets/published-plugins.json',import.meta.url)));
 assert.deepEqual(native,descriptor);
});
