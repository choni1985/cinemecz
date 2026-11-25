package com.ejemplo.tmdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class conexion_tmdb {

    private static final String BASE_URL = "https://api.themoviedb.org/3";
    private static final String LANGUAGE = "es-MX";
    private static final String REGION   = "MX";

    private static final String API_KEY      = System.getenv("TMDB_API_KEY");
    private static final String BEARER_TOKEN = System.getenv("TMDB_BEARER_TOKEN");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    public conexion_tmdb() {
        if ((API_KEY == null || API_KEY.isBlank())
                && (BEARER_TOKEN == null || BEARER_TOKEN.isBlank())) {
            throw new IllegalStateException(
                "Define TMDB_API_KEY (v3) o TMDB_BEARER_TOKEN (v4) en el entorno.");
        }
    }

    private boolean usaBearer() {
        return BEARER_TOKEN != null && !BEARER_TOKEN.isBlank();
    }

    public tmdb_page populares(int page) throws tmdb_status {
        return pagina("/movie/popular", page);
    }

    public tmdb_page cartelera(int page) throws tmdb_status {
        return pagina("/movie/now_playing", page);
    }

    public tmdb_page proximosEstrenos(int page) throws tmdb_status {
        return pagina("/movie/upcoming", page);
    }

    public pelicula detalle(int movieId) throws tmdb_status {
        String body = get("/movie/" + movieId);
        try {
            return mapper.readValue(body, pelicula.class);
        } catch (IOException e) {
            throw new tmdb_status(502, "Error parseando pelicula: " + e.getMessage());
        }
    }

    public String clasificacion(int movieId) {
        try {
            String body = get("/movie/" + movieId + "/release_dates");
            JsonNode root = mapper.readTree(body);
            for (String pais : new String[]{"MX", "US"}) {
                for (JsonNode r : root.path("results")) {
                    if (pais.equals(r.path("iso_3166_1").asText())) {
                        for (JsonNode rd : r.path("release_dates")) {
                            String c = rd.path("certification").asText();
                            if (c != null && !c.isEmpty()) return c;
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        return "NR";
    }

    public List<elenco_dto> reparto(int movieId) throws tmdb_status {
        String body = get("/movie/" + movieId + "/credits");
        try {
            JsonNode root = mapper.readTree(body);
            List<elenco_dto> lista = new ArrayList<>();
            for (JsonNode c : root.path("cast")) {
                elenco_dto dto = new elenco_dto();
                dto.setId(c.path("id").asInt());
                dto.setNombre(c.path("name").asText());
                dto.setPersonaje(c.path("character").asText());
                dto.setOrden(c.path("order").asInt());
                String foto = c.path("profile_path").isNull() ? null : c.path("profile_path").asText();
                dto.setFoto(foto != null ? pelicula_dto.IMG_BASE + foto : null);
                lista.add(dto);
            }
            return lista;
        } catch (IOException e) {
            throw new tmdb_status(502, "Error parseando reparto: " + e.getMessage());
        }
    }

    private tmdb_page pagina(String path, int page) throws tmdb_status {
        String body = get(path + "?page=" + Math.max(1, page));
        try {
            return mapper.readValue(body, tmdb_page.class);
        } catch (IOException e) {
            throw new tmdb_status(502, "Error parseando pagina: " + e.getMessage());
        }
    }

    private String get(String pathConQuery) throws tmdb_status {
        StringBuilder url = new StringBuilder(BASE_URL).append(pathConQuery);
        url.append(pathConQuery.contains("?") ? "&" : "?");
        url.append("language=").append(LANGUAGE);

        if (!usaBearer()) {
            url.append("&api_key=").append(API_KEY);
        } else {
            if (pathConQuery.contains("/movie/popular")
                    || pathConQuery.contains("/movie/now_playing")
                    || pathConQuery.contains("/movie/upcoming")) {
                url.append("&region=").append(REGION);
            }
        }

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "CinemexAPI/1.0")
                .GET();

        if (usaBearer()) b.header("Authorization", "Bearer " + BEARER_TOKEN);

        try {
            HttpResponse<String> resp = httpClient.send(b.build(),
                    HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 200) return resp.body();
            if (code == 404) throw new tmdb_status(404, "Pelicula no encontrada en TMDB");
            if (code == 401) throw new tmdb_status(502, "Credenciales de TMDB invalidas");
            throw new tmdb_status(502, "TMDB respondio HTTP " + code);
        } catch (IOException | InterruptedException e) {
            throw new tmdb_status(502, "Error de conexion con TMDB: " + e.getMessage());
        }
    }
}
