import http from 'node:http';
let windowStart=Date.now(), count=0;
const server=http.createServer((req,res)=>{
 if(req.url==='/health'){res.end('ok');return;}
 if(req.method!=='POST'||req.url!=='/transactions'){res.writeHead(404).end();return;}
 let raw='';req.on('data',chunk=>{raw+=chunk;if(raw.length>16384)req.destroy();});
 req.on('end',()=>{
  let tx;try{tx=JSON.parse(raw);}catch{res.writeHead(400).end();return;}
  const reply=(status)=>{res.writeHead(status,{'Content-Type':'application/json','X-Correlation-ID':req.headers['x-correlation-id']||'none'});res.end(JSON.stringify({id:tx.id,status,traceparent:req.headers.traceparent}));};
  switch(tx.scenario){
   case 'ERROR':reply(500);break;
   case 'NEVER':break;
   case 'SLOW':{const timer=setTimeout(()=>reply(200),3000);res.on('close',()=>clearTimeout(timer));break;}
   case 'RATE_LIMIT':if(Date.now()-windowStart>=1000){windowStart=Date.now();count=0;}reply(++count>5?429:200);break;
   default:reply(200);
  }
 });
});
server.listen(Number(process.env.PORT||9091),'0.0.0.0');
process.on('SIGTERM',()=>{server.close();server.closeAllConnections();});
