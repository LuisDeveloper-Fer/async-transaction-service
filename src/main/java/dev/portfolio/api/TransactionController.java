package dev.portfolio.api;
import dev.portfolio.application.Dispatcher;
import dev.portfolio.domain.Transaction;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.util.*;
@RestController
@RequestMapping("/api")
public class TransactionController {
 private final Dispatcher dispatcher;
 public TransactionController(Dispatcher dispatcher){this.dispatcher=dispatcher;}
 @PostMapping("/transactions")
 public ResponseEntity<Transaction> submit(@Valid @RequestBody Submission body,
   @RequestHeader(value="X-Correlation-ID",required=false) String correlation,
   @RequestHeader(value="traceparent",required=false) String parent){
   if(correlation!=null && !correlation.matches("[a-zA-Z0-9._-]{1,64}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid correlation ID");
   String trace=UUID.randomUUID().toString().replace("-","");
   if(parent!=null){
     if(!parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]") || parent.substring(3,35).matches("0{32}") || parent.substring(36,52).matches("0{16}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid traceparent");
     trace=parent.substring(3,35);
   }
   var tx=dispatcher.submit(body,correlation==null?UUID.randomUUID().toString():correlation,trace);
   return ResponseEntity.accepted().location(URI.create("/api/transactions/"+tx.id())).header("X-Correlation-ID",tx.correlationId()).body(tx);
 }
 @GetMapping("/transactions/{id}") public Transaction get(@PathVariable UUID id){return dispatcher.get(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Transaction not found or expired"));}
 @GetMapping("/transactions") public List<Transaction> recent(){return dispatcher.recent();}
 @GetMapping("/system") public Map<String,Object> stats(){return dispatcher.stats();}
 @ExceptionHandler(Dispatcher.Rejected.class) public ResponseEntity<ProblemDetail> rejected(Dispatcher.Rejected e){
   var problem=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.status),e.getMessage());problem.setProperty("code",e.getMessage());
   return ResponseEntity.status(e.status).header("Retry-After","1").body(problem);
 }
}
