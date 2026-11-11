package com.ejemplo.tmdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class server_api {

    private final conexion_tmdb tmdb;
    private final ObjectMapper mapper = new ObjectMapper();

    public server_api() {
        this.tmdb = new conexion_tmdb();
    }

    @FunctionName("CinemexApi")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET}, authLevel = AuthorizationLevel.ANONYMOUS, route = "{*route}")
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        String path = request.getUri().getPath();
        String method = request.getHttpMethod().name();

        try {
            if (!"GET".equalsIgnoreCase(method)) {
                return sendError(request, HttpStatus.METHOD_NOT_ALLOWED, "Metodo no permitido");
            }

            if (path.equals("/") || path.equals("/health")) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("servicio", "Cinemex API");
                body.put("status", "ok");
                body.put("endpoints", List.of(
                        "/peliculas",
                        "/peliculas/populares",
                        "/peliculas/cartelera",
                        "/peliculas/proximos-estrenos",
                        "/peliculas/{id}",
                        "/peliculas/{id}/reparto"));
                return sendJson(request, HttpStatus.OK, body);
            }

            if (!path.startsWith("/peliculas")) {
                return sendError(request, HttpStatus.NOT_FOUND, "Ruta no encontrada");
            }

            String resto = path.substring("/peliculas".length());
            if (resto.startsWith("/")) resto = resto.substring(1);
            if (resto.endsWith("/")) resto = resto.substring(0, resto.length() - 1);

            int page = extraerPage(request);

            switch (resto) {
                case "":
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

                    return sendError(request, HttpStatus.NOT_FOUND, "Ruta no encontrada");
            }

        } catch (NumberFormatException e) {
            return sendError(request, HttpStatus.BAD_REQUEST, "ID de pelicula invalido");
        } catch (tmdb_status e) {
            return sendError(request, HttpStatus.valueOf(e.getStatusCode()), e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return sendError(request, HttpStatus.INTERNAL_SERVER_ERROR, "Error interno: " + e.getMessage());
        }
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
                    .body("Error serializando respuesta: " + e.getMessage())
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
