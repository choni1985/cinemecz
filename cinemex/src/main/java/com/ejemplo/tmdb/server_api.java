package com.ejemplo.tmdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class server_api {

    private final ObjectMapper mapper = new ObjectMapper();

    @FunctionName("CinemexApi")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET}, authLevel = AuthorizationLevel.ANONYMOUS, route = "peliculas/{*route}")
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        try {
            String path = request.getUri().getPath();
            String method = request.getHttpMethod().name();

            if (!"GET".equalsIgnoreCase(method)) {
                return sendError(request, HttpStatus.METHOD_NOT_ALLOWED, "Metodo no permitido");
            }

            String resto = path.replace("/api/peliculas", "").replace("/peliculas", "");
            if (resto.startsWith("/")) resto = resto.substring(1);
            if (resto.endsWith("/")) resto = resto.substring(0, resto.length() - 1);

            int page = extraerPage(request);

            // Rutas que no dependen de TMDB
            switch (resto) {
                case "swagger":
                    return sendSwaggerUI(request);
                case "openapi.json":
                    return sendOpenApiSpec(request);
                case "docs":
                    return sendIndexHtml(request);
                case "":
                case "health":
                    return sendHealth(request);
                default:
                    break;
            }

            // Rutas que sí dependen de TMDB
            conexion_tmdb tmdb = new conexion_tmdb();

            switch (resto) {
                case "populares":
                    return enviarLista(request, tmdb.populares(page));
                case "cartelera":
                    return enviarLista(request, tmdb.cartelera(page));
                case "proximos-estrenos":
                    return enviarLista(request, tmdb.proximosEstrenos(page));
                default:
                    String[] partes = resto.split("/");

                    if (partes.length == 1) {
                        int id = parseId(partes[0]);
                        pelicula p = tmdb.detalle(id);
                        String clas = tmdb.clasificacion(id);
                        return sendJson(request, HttpStatus.OK, pelicula_dto.from(p, clas));
                    }

                    if (partes.length == 2 && "reparto".equals(partes[1])) {
                        int id = parseId(partes[0]);
                        return sendJson(request, HttpStatus.OK, tmdb.reparto(id));
                    }

                    return sendError(request, HttpStatus.NOT_FOUND, "Ruta no encontrada en la API");
            }

        } catch (IllegalStateException e) {
            return sendError(request, HttpStatus.INTERNAL_SERVER_ERROR, "Configuracion faltante: " + e.getMessage());
        } catch (NumberFormatException e) {
            return sendError(request, HttpStatus.BAD_REQUEST, "ID de pelicula invalido");
        } catch (tmdb_status e) {
            return sendError(request, HttpStatus.valueOf(e.getStatusCode()), e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return sendError(request, HttpStatus.INTERNAL_SERVER_ERROR, "Error interno: " + e.getMessage());
        }
    }

    private HttpResponseMessage sendHealth(HttpRequestMessage<Optional<String>> request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("servicio", "Cinemex API");
        body.put("status", "ok");
        body.put("endpoints", List.of(
                "/api/peliculas",
                "/api/peliculas/health",
                "/api/peliculas/populares",
                "/api/peliculas/cartelera",
                "/api/peliculas/proximos-estrenos",
                "/api/peliculas/{id}",
                "/api/peliculas/{id}/reparto",
                "/api/peliculas/docs",
                "/api/peliculas/openapi.json",
                "/api/peliculas/swagger"
        ));
        return sendJson(request, HttpStatus.OK, body);
    }

    private HttpResponseMessage sendIndexHtml(HttpRequestMessage<Optional<String>> request) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("index.html")) {
            if (is != null) {
                String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return request.createResponseBuilder(HttpStatus.OK)
                        .header("Content-Type", "text/html; charset=utf-8")
                        .header("Access-Control-Allow-Origin", "*")
                        .body(html)
                        .build();
            } else {
                return sendError(request, HttpStatus.NOT_FOUND, "Archivo index.html no encontrado en resources");
            }
        } catch (Exception e) {
            return sendError(request, HttpStatus.INTERNAL_SERVER_ERROR, "Error leyendo index.html: " + e.getMessage());
        }
    }

    private HttpResponseMessage sendOpenApiSpec(HttpRequestMessage<Optional<String>> request) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("openapi.json")) {
            if (is != null) {
                String spec = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return request.createResponseBuilder(HttpStatus.OK)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("Access-Control-Allow-Origin", "*")
                        .body(spec)
                        .build();
            } else {
                return sendError(request, HttpStatus.NOT_FOUND, "Archivo openapi.json no encontrado en resources");
            }
        } catch (Exception e) {
            return sendError(request, HttpStatus.INTERNAL_SERVER_ERROR, "Error leyendo OpenAPI: " + e.getMessage());
        }
    }

    private HttpResponseMessage sendSwaggerUI(HttpRequestMessage<Optional<String>> request) {
        String html = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "  <title>Cinemex API - Swagger</title>\n" +
                "  <link rel=\"stylesheet\" type=\"text/css\" href=\"https://unpkg.com/swagger-ui-dist@5/swagger-ui.css\" />\n" +
                "</head>\n" +
                "<body>\n" +
                "<div id=\"swagger-ui\"></div>\n" +
                "<script src=\"https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js\"></script>\n" +
                "<script>\n" +
                "  window.onload = function() {\n" +
                "    SwaggerUIBundle({\n" +
                "      url: '/api/peliculas/openapi.json',\n" +
                "      dom_id: '#swagger-ui',\n" +
                "      deepLinking: true,\n" +
                "      presets: [SwaggerUIBundle.presets.apis]\n" +
                "    });\n" +
                "  };\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
        return request.createResponseBuilder(HttpStatus.OK)
                .header("Content-Type", "text/html; charset=utf-8")
                .header("Access-Control-Allow-Origin", "*")
                .body(html)
                .build();
    }

    private int parseId(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new NumberFormatException("ID invalido: " + s);
        }
    }

    private int extraerPage(HttpRequestMessage<Optional<String>> request) {
        String q = request.getUri().getQuery();
        if (q != null) {
            for (String par : q.split("&")) {
                if (par.startsWith("page=")) {
                    try {
                        return Math.max(1, Integer.parseInt(par.substring(5)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return 1;
    }

    private HttpResponseMessage enviarLista(HttpRequestMessage<Optional<String>> request, tmdb_page pagina) {
        List<pelicula_dto> dtos = pagina.getResults().stream()
                .map(pelicula_dto::from)
                .toList();

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("pagina", pagina.getPage());
        respuesta.put("totalPaginas", pagina.getTotalPages());
        respuesta.put("totalResultados", pagina.getTotalResults());
        respuesta.put("peliculas", dtos);

        return sendJson(request, HttpStatus.OK, respuesta);
    }

    private HttpResponseMessage sendJson(HttpRequestMessage<Optional<String>> request, HttpStatus status, Object body) {
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
            return request.createResponseBuilder(status)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Access-Control-Allow-Origin", "*")
                    .body(json)
                    .build();
        } catch (Exception e) {
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Error serializando respuesta: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    private HttpResponseMessage sendError(HttpRequestMessage<Optional<String>> request, HttpStatus status, String mensaje) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", mensaje);
        return sendJson(request, status, body);
    }
}
