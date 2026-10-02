import {readFileSync,mkdirSync,mkdtempSync,writeFileSync,renameSync,rmSync} from 'node:fs';
import {join,dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {verifyPackage} from './package-verification.mjs';
import {readCatalog} from '../../tools/plugin-download-codes.mjs';
const AUTHOR='39dca3d132c56262c0873ec96faf22cc8ed9ca30c7ed1f135049f8570b943adc';
const PROVIDERS=['nl.neerdael.beatport','nl.neerdael.spotify','nl.neerdael.youtube-music'];
async function readPackage(_url,row){
 const directory=fileURLToPath(new URL('../packages/',import.meta.url));
 return readFileSync(join(directory,row.id.split('.').pop()+'.mbplugin'));
}
export async function stagePublishedPlugins({descriptor,catalog,output,readPackage:fetch=readPackage,expectedAuthor=AUTHOR,expectedIds=PROVIDERS}){
 if(descriptor.format!==1 || !Array.isArray(descriptor.plugins) || descriptor.plugins.length!==expectedIds.length)throw new Error('Invalid published plugin descriptor');
 const ids=new Set(),names=new Set();
 const sources=descriptor.plugins.map(row=>{
  if(!expectedIds.includes(row.id) || ids.has(row.id) || row.fingerprint!==expectedAuthor || !/^[a-f0-9]{64}$/.test(row.sha256) || !Number.isInteger(row.size) || row.size<=0 || row.size>64*1024*1024 || !/^[0-9]{3}$/.test(row.code))throw new Error('Invalid published plugin identity');
  ids.add(row.id);const name=row.id.split('.').pop();if(!/^[a-z0-9_-]+$/.test(name) || names.has(name))throw new Error('Invalid package filename');names.add(name);
  const entry=catalog.find(entry=>entry.code===row.code);if(!entry || entry.id!==row.id)throw new Error('Published code does not match the plugin catalog');
  return {row,name,url:entry.url};
 });
 mkdirSync(dirname(output),{recursive:true});const staging=mkdtempSync(join(dirname(output),'.plugins-'));
 try{
  for(const {row,name,url} of sources){
   const bytes=await fetch(url,row),value=verifyPackage(bytes,{id:row.id,fingerprint:expectedAuthor});
   if(value.sha256!==row.sha256 || bytes.length!==row.size || value.manifest.version!==row.version || value.manifest.versionCode!==row.versionCode)throw new Error('Downloaded package does not match the publication');
   writeFileSync(join(staging,name+'.mbplugin'),bytes);
  }
  rmSync(output,{recursive:true,force:true});renameSync(staging,output);return descriptor.plugins;
 }finally{rmSync(staging,{recursive:true,force:true});}
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const root=fileURLToPath(new URL('../../',import.meta.url));
 try{
  const descriptor=JSON.parse(readFileSync(join(root,'plugins/published.json'),'utf8'));
  const catalog=readCatalog(join(root,'app/src/main/assets/plugin-download-catalog.json')).entries;
  await stagePublishedPlugins({descriptor,catalog,output:join(root,'plugins/dist')});console.log('Verified all published first-party packages');
 }catch(error){console.error(error.message);process.exitCode=1;}
}
