// Prueba de carga del servidor (RNF13: al menos 1,500 solicitudes por minuto sin pasar de 2 s de respuesta).
// Se corre con Java 21, sin instalar nada:   java Carga.java
// Opcional: otra direccion y otra duracion   java Carga.java https://otro-servidor/api/v1 120
//
// Manda solicitudes a ritmo fijo, sin esperar a que termine la anterior, a dos rutas que leen la base de datos
// y no crean nada ni mandan avisos:
//   GET /salud                     (revisa la base)          -> se espera 200
//   GET /enlace/{token al azar}    (busca el enlace en la base) -> se espera 404
// Etapas: 1,500/min (lo que pide RNF13) y despues 3,000/min (el doble, para ver el margen).
// Al final imprime y guarda en resultado-carga.txt cuantas respondieron bien y en cuanto tiempo.

import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Carga {

    static final String URL_NUBE = "https://sendero-seguro-bse3efh5ddexczcx.mexicocentral-01.azurewebsites.net/api/v1";
    static final long LIMITE_MS = 2000;   // RNF3: respuesta en 2 s como maximo

    record Resultado(String ruta, int estado, long ms, boolean esperado) {}

    record Etapa(String nombre, int porMinuto, int segundos) {}

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0].replaceAll("/+$", "") : URL_NUBE;
        int segundos = args.length > 1 ? Integer.parseInt(args[1]) : 120;
        List<Etapa> etapas = List.of(
            new Etapa("RNF13: 1,500 por minuto", 1500, segundos),
            new Etapa("Margen: 3,000 por minuto", 3000, Math.max(30, segundos / 2))
        );

        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

        StringBuilder informe = new StringBuilder();
        linea(informe, "Prueba de carga de Sendero Seguro");
        linea(informe, "Fecha: " + ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        linea(informe, "Servidor: " + base);
        linea(informe, "Rutas: GET /salud (200) y GET /enlace/{token} (404), mitad y mitad");
        linea(informe, "");

        // Calentar: que Azure tenga la app despierta y las conexiones abiertas antes de medir.
        System.out.println("Calentando el servidor...");
        for (int i = 0; i < 10; i++) pedir(http, base, i);

        boolean cumple = true;
        for (Etapa etapa : etapas) {
            System.out.printf("%nEtapa '%s' durante %d s...%n", etapa.nombre(), etapa.segundos());
            List<Resultado> resultados = correr(http, base, etapa);
            cumple &= resumir(informe, etapa, resultados, etapa.porMinuto() == 1500);
        }

        linea(informe, cumple
            ? "CONCLUSION: CUMPLE RNF13. A 1,500 solicitudes por minuto respondio bien al menos el 99% y el 95% tardo menos de 2 s."
            : "CONCLUSION: NO CUMPLE RNF13 en esta corrida. Revisa los errores y tiempos de arriba.");

        Path archivo = Path.of("resultado-carga.txt");
        Files.writeString(archivo, informe.toString());
        System.out.println();
        System.out.println(informe);
        System.out.println("Guardado en " + archivo.toAbsolutePath());
    }

    /** Lanza [porMinuto] solicitudes por minuto a ritmo fijo durante [segundos] y espera las respuestas. */
    static List<Resultado> correr(HttpClient http, String base, Etapa etapa) throws InterruptedException {
        ConcurrentLinkedQueue<Resultado> resultados = new ConcurrentLinkedQueue<>();
        var lanzador = Executors.newSingleThreadScheduledExecutor();
        var trabajadores = Executors.newVirtualThreadPerTaskExecutor();
        int total = etapa.porMinuto() * etapa.segundos() / 60;
        long cadaMicros = 60_000_000L / etapa.porMinuto();
        AtomicInteger lanzadas = new AtomicInteger();

        lanzador.scheduleAtFixedRate(() -> {
            int n = lanzadas.getAndIncrement();
            if (n >= total) return;
            trabajadores.submit(() -> resultados.add(pedir(http, base, n)));
            if (n > 0 && n % (etapa.porMinuto() / 6) == 0) System.out.printf("  %d de %d enviadas%n", n, total);
        }, 0, cadaMicros, TimeUnit.MICROSECONDS);

        while (lanzadas.get() < total) Thread.sleep(100);
        lanzador.shutdownNow();
        trabajadores.shutdown();
        trabajadores.awaitTermination(30, TimeUnit.SECONDS);
        return new ArrayList<>(resultados);
    }

    static final SecureRandom azar = new SecureRandom();

    static Resultado pedir(HttpClient http, String base, int n) {
        boolean salud = n % 2 == 0;
        String ruta;
        int esperado;
        if (salud) {
            ruta = "/salud";
            esperado = 200;
        } else {
            byte[] b = new byte[32];
            azar.nextBytes(b);
            ruta = "/enlace/" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);   // 43 caracteres, no existe
            esperado = 404;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + ruta))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "sendero-prueba-carga")
            .GET().build();
        long inicio = System.nanoTime();
        try {
            HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            return new Resultado(salud ? "/salud" : "/enlace", r.statusCode(), ms, r.statusCode() == esperado);
        } catch (Exception e) {
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            return new Resultado(salud ? "/salud" : "/enlace", -1, ms, false);
        }
    }

    /** Escribe el resumen de una etapa. Regresa si cumple (solo cuenta para la etapa de RNF13). */
    static boolean resumir(StringBuilder informe, Etapa etapa, List<Resultado> rs, boolean cuenta) {
        List<Long> tiempos = new ArrayList<>(rs.stream().map(Resultado::ms).toList());
        Collections.sort(tiempos);
        long bien = rs.stream().filter(Resultado::esperado).count();
        long bajoLimite = rs.stream().filter(r -> r.esperado() && r.ms() <= LIMITE_MS).count();
        Map<String, Integer> codigos = new TreeMap<>();
        for (Resultado r : rs) codigos.merge(r.estado() == -1 ? "sin respuesta" : String.valueOf(r.estado()), 1, Integer::sum);

        double pctBien = 100.0 * bien / Math.max(1, rs.size());
        double pctLimite = 100.0 * bajoLimite / Math.max(1, rs.size());
        long p95 = percentil(tiempos, 95);

        linea(informe, "== " + etapa.nombre() + " ==");
        linea(informe, String.format("Duracion: %d s. Solicitudes: %d (%d por minuto)", etapa.segundos(), rs.size(), etapa.porMinuto()));
        linea(informe, String.format("Respondieron bien: %d (%.2f%%)", bien, pctBien));
        linea(informe, "Codigos: " + codigos);
        linea(informe, String.format("Tiempo de respuesta (ms): mediana %d, p90 %d, p95 %d, p99 %d, maximo %d",
            percentil(tiempos, 50), percentil(tiempos, 90), p95, percentil(tiempos, 99),
            tiempos.isEmpty() ? 0 : tiempos.get(tiempos.size() - 1)));
        linea(informe, String.format("Bien y en menos de 2 s: %.2f%%", pctLimite));
        linea(informe, "");
        return !cuenta || (pctBien >= 99.0 && p95 <= LIMITE_MS);
    }

    static long percentil(List<Long> ordenados, int p) {
        if (ordenados.isEmpty()) return 0;
        int i = (int) Math.ceil(p / 100.0 * ordenados.size()) - 1;
        return ordenados.get(Math.max(0, Math.min(i, ordenados.size() - 1)));
    }

    static void linea(StringBuilder sb, String s) {
        sb.append(s).append(System.lineSeparator());
    }
}
