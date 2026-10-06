import { Component, DestroyRef, computed, inject, signal } from "@angular/core";
import { bootstrapApplication } from "@angular/platform-browser";
import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
} from "@angular/common/http";
import { JsonPipe } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { timer, EMPTY, catchError, exhaustMap } from "rxjs";
import { config } from "./config";

type Row = Record<string, unknown>;
@Component({
  selector: "app-root",
  standalone: true,
  imports: [FormsModule, JsonPipe],
  templateUrl: "./app.html",
})
class App {
  readonly config = config;
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  readonly rows = signal<Row[]>([]);
  readonly selected = signal<Row | null>(null);
  readonly busy = signal(false);
  readonly online = signal(false);
  readonly message = signal("");
  readonly lastChecked = signal("");
  readonly selectedId = computed(() => String(this.selected()?.["id"] || ""));
  readonly failures = computed(
    () =>
      this.rows().filter((row) =>
        ["ERROR", "FAILED", "DECLINED"].includes(String(row["status"])),
      ).length,
  );
  readonly outcomes: Record<string, string> = {
    QUEUED: "Recibida",
    RUNNING: "En proceso",
    SUCCEEDED: "Completada",
    FAILED: "No completada",
    CANCELLED: "Cancelada",
    APPROVED: "Aprobado",
    DECLINED: "Declinado",
    REVERSED: "Reversado",
    PENDING: "Pendiente",
    SENDING: "Enviando",
    DELIVERED: "Entregada",
    SUCCESS: "Correcta",
    ERROR: "Error",
  };
  readonly labels: Record<string, string> = {
    id: "Referencia",
    status: "Estado",
    scenario: "Escenario",
    error: "Motivo",
    amount: "Importe",
    responseCode: "Código",
    attempts: "Intentos",
    nextAttempt: "Próximo intento",
    discrepancyCount: "Diferencias",
    internalRows: "Filas internas",
    providerRows: "Filas proveedor",
    subject: "Identidad",
    roles: "Permisos",
    issuer: "Emisor",
    service: "Servicio",
    durationMs: "Duración (ms)",
  };
  amount = 125.5;
  currency = "PEN";
  scenario = config.kind === "payment" ? "APPROVED" : "SUCCESS";
  idempotencyKey = crypto.randomUUID();
  eventType = "payment.approved";
  eventMessage = "fictional-payment";
  internalCsv = "reference,amount,currency\nTX001,100.00,PEN\nTX002,50.00,USD";
  providerCsv = "reference,amount,currency\nTX001,99.00,PEN\nTX003,50.00,USD";
  token = "";
  endpoint = "/api/me";
  owner = "";
  service = "PAYMENTS";
  outcome = "SUCCESS";
  durationMs = 250;
  activeScenario = "normal";
  constructor() {
    if (config.kind !== "secure")
      timer(0, 3000)
        .pipe(
          exhaustMap(() =>
            this.http.get<unknown>(config.list).pipe(
              catchError(() => {
                this.online.set(false);
                return EMPTY;
              }),
            ),
          ),
          takeUntilDestroyed(this.destroy),
        )
        .subscribe((data) => this.receive(data));
  }
  display(value: unknown): string {
    return value === null || value === undefined
      ? "—"
      : typeof value === "object"
        ? JSON.stringify(value)
        : String(value);
  }
  status(row: Row | null): string {
    if (!row) return "Esperando tu primera operación";
    if (row["status"] && typeof row["status"] === "string")
      return this.outcomes[String(row["status"])] || String(row["status"]);
    if (typeof row["status"] === "number") return "Revisa la solicitud";
    if (row["discrepancyCount"] !== undefined)
      return Number(row["discrepancyCount"]) === 0
        ? "Los reportes coinciden"
        : "Comparación completada";
    if (row["subject"]) return "Acceso autorizado";
    return "Respuesta recibida";
  }
  body(): Row {
    switch (config.kind) {
      case "async":
      case "payment":
        return {
          amount: this.amount,
          currency: this.currency,
          scenario: this.scenario,
        };
      case "webhook":
        return { type: this.eventType, message: this.eventMessage };
      case "reconciliation":
        return { internalCsv: this.internalCsv, providerCsv: this.providerCsv };
      case "monitor":
        return {
          id: crypto.randomUUID(),
          service: this.service,
          status: this.outcome,
          durationMs: this.durationMs,
          occurredAt: new Date().toISOString(),
        };
      default:
        return {};
    }
  }
  headers(): Record<string, string> {
    const headers: Record<string, string> = {};
    if (config.kind === "payment")
      headers["Idempotency-Key"] = this.idempotencyKey;
    if (config.kind === "secure" && this.token)
      headers["Authorization"] = "Bearer " + this.token;
    return headers;
  }
  receive(data: unknown): void {
    const rows = Array.isArray(data)
      ? data
      : ((data as { items?: Row[] })?.items ?? [data as Row]);
    this.rows.set(rows);
    this.online.set(true);
    this.lastChecked.set(new Date().toLocaleTimeString());
    const current = this.selectedId();
    if (current) {
      const updated = rows.find((row: Row) => row["id"] === current);
      if (updated) this.selected.set(updated);
    }
  }
  refresh(): void {
    this.http
      .get<unknown>(config.list, { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (data) => this.receive(data),
        error: (e) => this.fail(e),
      });
  }
  fail(error: HttpErrorResponse): void {
    this.busy.set(false);
    this.online.set(error.status !== 0);
    this.message.set(
      error.status === 504
        ? "La respuesta se perdió. Consulta la referencia antes de repetir."
        : error.status === 403
          ? "Esta identidad no tiene permiso para ese recurso."
          : error.status === 401
            ? "Necesitas un token válido para continuar."
            : error.status
              ? "La solicitud no se completó (HTTP " +
                error.status +
                "). Revisa el detalle."
              : "No podemos conectar con el servicio. Comprueba que el backend esté iniciado.",
    );
    this.selected.set(
      error.error && typeof error.error === "object"
        ? error.error
        : { status: error.status, detail: this.message() },
    );
  }
  send(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.message.set("");
    const url =
      this.endpoint === "/api/accounts/"
        ? this.endpoint + encodeURIComponent(this.owner)
        : this.endpoint;
    const call =
      config.kind === "secure"
        ? this.http.get<Row>(url, {
            headers: this.headers(),
            observe: "response",
          })
        : this.http.post<Row>(config.post, this.body(), {
            headers: this.headers(),
            observe: "response",
          });
    call.pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (response) => {
        this.busy.set(false);
        this.online.set(true);
        this.selected.set(response.body);
        this.message.set(
          response.status === 202
            ? "Solicitud recibida. Puedes seguir su estado aquí."
            : "Listo. El resultado ya está disponible.",
        );
        if (config.kind === "secure") {
          if (response.body) this.rows.set([response.body]);
          this.lastChecked.set(new Date().toLocaleTimeString());
        } else this.refresh();
      },
      error: (error) => this.fail(error),
    });
  }
  action(url: string): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.http
      .post<Row>(url, {}, { headers: this.headers() })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (result) => {
          this.busy.set(false);
          this.selected.set(result);
          this.message.set("Operación actualizada.");
          this.refresh();
        },
        error: (e) => this.fail(e),
      });
  }
  scenarioChoice(choice: string): void {
    this.activeScenario = choice;
    if (config.kind === "async")
      this.scenario =
        choice === "normal" ? "SUCCESS" : choice === "slow" ? "SLOW" : "ERROR";
    if (config.kind === "payment")
      this.scenario =
        choice === "normal"
          ? "APPROVED"
          : choice === "slow"
            ? "LOST_RESPONSE"
            : "DECLINED";
    if (config.kind === "webhook")
      this.eventMessage =
        choice === "normal"
          ? "fictional-payment"
          : choice === "slow"
            ? "simulate-rate-limit"
            : "simulate-error";
  }
  newKey(): void {
    this.idempotencyKey = crypto.randomUUID();
  }
  async loadCsv(event: Event, side: "internal" | "provider"): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    if (file.size > 200000) {
      this.message.set("El archivo debe ocupar menos de 200 KB.");
      return;
    }
    try {
      const text = await file.text();
      if (side === "internal") this.internalCsv = text;
      else this.providerCsv = text;
    } catch {
      this.message.set("No se pudo leer el archivo.");
    }
  }
  differences(): Row[] {
    try {
      return JSON.parse(String(this.selected()?.["reportJson"] || "[]"));
    } catch {
      return [];
    }
  }
}
bootstrapApplication(App, { providers: [provideHttpClient()] }).catch(
  console.error,
);
