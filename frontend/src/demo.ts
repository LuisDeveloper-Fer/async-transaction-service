import {
  HttpErrorResponse,
  HttpInterceptorFn,
  HttpResponse,
} from "@angular/common/http";
import { of, throwError, delay } from "rxjs";
export const browserDemo =
  location.hostname.endsWith(".github.io") ||
  new URLSearchParams(location.search).get("demo") === "true";
type Row = Record<string, unknown>;
const records: Row[] = [];
const keys = new Map<string, { fingerprint: string; row: Row }>();
export const demoInterceptor: HttpInterceptorFn = (req, next) => {
  if (!browserDemo || !req.url.startsWith("/api/")) return next(req);
  const respond = (body: unknown, status = 200) =>
    of(new HttpResponse({ status, body: structuredClone(body) })).pipe(
      delay(180),
    );
  const fail = (status: number, detail: string) =>
    throwError(
      () => new HttpErrorResponse({ status, error: { status, detail } }),
    );
  if (req.method === "GET") return respond([...records].reverse());
  if (req.url.endsWith("/reversals")) {
    const row = records.find((r) => req.url.includes(String(r["id"])));
    if (!row) return fail(404, "Referencia inexistente");
    if (row["status"] !== "APPROVED" && row["status"] !== "REVERSED")
      return fail(409, "Solo se puede reversar un pago aprobado");
    row["status"] = "REVERSED";
    row["responseCode"] = "00";
    return respond(row);
  }
  const body = req.body as Row;
  if (
    !body ||
    Number(body["amount"]) <= 0 ||
    !["PEN", "USD", "EUR"].includes(String(body["currency"]))
  )
    return fail(400, "Importe positivo y moneda válida requeridos");
  const payment = req.url.includes("payments");
  const allowed = payment
    ? ["APPROVED", "DECLINED", "TIMEOUT", "LOST_RESPONSE"]
    : ["SUCCESS", "SLOW", "ERROR", "NEVER", "RATE_LIMIT"];
  if (!allowed.includes(String(body["scenario"])))
    return fail(400, "Escenario no válido");
  const key = req.headers.get("Idempotency-Key") || "";
  const fingerprint = JSON.stringify(body);
  if (payment) {
    if (!key) return fail(400, "Falta Idempotency-Key");
    const previous = keys.get(key);
    if (previous)
      return previous.fingerprint === fingerprint
        ? respond(previous.row)
        : fail(409, "Esta clave corresponde a otra solicitud");
  }
  if (records.length >= 100)
    return fail(429, "Demo limitada a 100 registros. Recarga para reiniciar.");
  const row: Row = {
    ...body,
    id: crypto.randomUUID(),
    status: payment
      ? body["scenario"] === "DECLINED"
        ? "DECLINED"
        : body["scenario"] === "TIMEOUT"
          ? "PENDING"
          : "APPROVED"
      : "QUEUED",
    responseCode: body["scenario"] === "DECLINED" ? "05" : "00",
    error: null,
  };
  records.push(row);
  if (payment) {
    keys.set(key, { fingerprint, row });
    if (body["scenario"] === "LOST_RESPONSE")
      return fail(
        504,
        "Respuesta perdida simulada. Consulta la actividad antes de repetir.",
      );
  } else
    setTimeout(
      () => {
        row["status"] = body["scenario"] === "SUCCESS" ? "SUCCEEDED" : "FAILED";
        row["error"] =
          body["scenario"] === "SUCCESS"
            ? null
            : "Simulación: " + body["scenario"];
      },
      body["scenario"] === "SUCCESS" ? 900 : 2500,
    );
  return respond(row, payment ? 201 : 202);
};
