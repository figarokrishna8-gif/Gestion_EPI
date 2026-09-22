import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class Main {
    private static final String DB_URL = "jdbc:mysql://localhost:3306/gestion_epi";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "";

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        
        server.createContext("/", new StaticFileHandler());
        server.createContext("/login", new LoginHandler());
        server.createContext("/dashboard.html", new DashboardHandler());
        server.createContext("/ajouter-epi", new AjouterEpiHandler());
        server.createContext("/mouvement-stock", new MouvementStockHandler());
        server.createContext("/supprimer-epi", new SupprimerEpiHandler());
        server.createContext("/logout", new LogoutHandler());
        server.createContext("/creer-utilisateur", new CreerUtilisateurHandler());
        server.createContext("/mon_espace.html", new MonEspaceHandler());
        
        server.createContext("/distributions.html", new DistributionsPageHandler());
        server.createContext("/attribuer-epi", new AttribuerEpiHandler());
        server.createContext("/supprimer-distribution", new SupprimerDistributionHandler());
        server.createContext("/modifier-distribution", new ModifierDistributionHandler());

        server.createContext("/commandes.html", new CommandesPageHandler());
        server.createContext("/creer-commande", new CreerCommandeHandler());
        server.createContext("/recevoir-commande", new RecevoirCommandeHandler());

        server.setExecutor(null);
        System.out.println("Serveur SPAT demarre sur http://localhost:8080");
        server.start();
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            File file = new File("web" + path);
            if (!file.exists() || file.isDirectory()) {
                sendResponse(exchange, 404, "Fichier introuvable.");
                return;
            }
            String contentType = "text/html; charset=UTF-8";
            if (path.endsWith(".png")) contentType = "image/png";
            else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) contentType = "image/jpeg";
            else if (path.endsWith(".css")) contentType = "text/css";
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, fileBytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(fileBytes); }
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Methode non autorisee");
                return;
            }
            InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
            BufferedReader br = new BufferedReader(isr);
            Map<String, String> inputs = parseFormData(br.readLine());
            String matricule = inputs.get("matricule");
            String password = inputs.get("password");
            String roleTrouve = null;

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                String sql = "SELECT role FROM utilisateurs WHERE matricule = ? AND mot_de_passe = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, matricule);
                    ps.setString(2, password);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) roleTrouve = rs.getString("role"); }
                }
            } catch (SQLException e) { e.printStackTrace(); }

            if (roleTrouve != null) {
                boolean dashboardAccess = "Administrateur / Gestionnaire EPI".equals(roleTrouve)
                    || "Responsable HSTE".equals(roleTrouve)
                    || "Chef de service".equals(roleTrouve);
                String destination = dashboardAccess
                    ? "/dashboard.html?role=" + java.net.URLEncoder.encode(roleTrouve, "UTF-8")
                    : "/mon_espace.html?matricule=" + java.net.URLEncoder.encode(matricule, "UTF-8");
                exchange.getResponseHeaders().set("Location", destination);
                exchange.sendResponseHeaders(303, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                String errorHtml = "<html><body style=\"font-family:sans-serif; text-align:center; padding-top:50px;\"><h2 style=\"color:red;\">❌ Numero Matricule ou Mot de passe incorrect.</h2><a href=\"/index.html\">Retourner a la page de connexion</a></body></html>";
                byte[] bytes = errorHtml.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            }
        }
    }

    static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String role = "Profil non identifie";
            if (query != null && query.contains("role=")) {
                String roleParameter = parseFormData(query).getOrDefault("role", role);
                role = URLDecoder.decode(roleParameter, "UTF-8");
            }
            if (isTerrainRole(role)) {
                sendResponse(exchange, 403, "403 Interdit");
                return;
            }

            File file = new File("web/dashboard.html");
            BufferedReader reader = new BufferedReader(new FileReader(file));
            StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) html.append(line).append("\n");
            reader.close();

            String output = html.toString().replace("Profil non identifie", role);
            boolean administrator = "Administrateur / Gestionnaire EPI".equals(role);
            output = setSectionVisibility(output, "section-gestion-utilisateurs", administrator);
            output = setSectionVisibility(output, "section-creer-epi", administrator);
            output = setActorsLinkVisibility(output, administrator);

            StringBuilder stockRows = new StringBuilder();
            StringBuilder movementOptions = new StringBuilder();
            String stockSql = "SELECT t.id_type, t.nom_type, t.taille_pointure, "
                    + "COALESCE(SUM(CASE WHEN UPPER(h.type_mouvement) = 'ENTREE' THEN h.quantite ELSE 0 END), 0) AS total_entrees, "
                    + "COALESCE(SUM(CASE WHEN UPPER(h.type_mouvement) = 'SORTIE' THEN h.quantite ELSE 0 END), 0) AS total_sorties "
                    + "FROM types_epi t LEFT JOIN historique_mouvements h ON h.id_type = t.id_type "
                    + "GROUP BY t.id_type, t.nom_type, t.taille_pointure ORDER BY t.nom_type";

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 PreparedStatement ps = conn.prepareStatement(stockSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int idType = rs.getInt("id_type");
                    String nomType = rs.getString("nom_type");
                    String taille = rs.getString("taille_pointure");
                    int totalEntrees = rs.getInt("total_entrees");
                    int totalSorties = rs.getInt("total_sorties");
                    int totalDisponible = totalEntrees - totalSorties;
                    String stockAlertStyle = "border-bottom: 1px solid #eee;";
                    String stockAlert = "";
                    if (totalDisponible == 0) {
                        stockAlertStyle = "border-bottom: 1px solid #eee; background-color: #f8d7da; color: #721c24; animation: blink 1.5s infinite;";
                        stockAlert = " 🚨";
                    } else if (totalDisponible > 0 && totalDisponible <= 2) {
                        stockAlertStyle = "border-bottom: 1px solid #eee; background-color: #fff3cd; color: #856404;";
                        stockAlert = " ⚠️";
                    }

                    stockRows.append("<tr style=\"").append(stockAlertStyle).append("\">")
                            .append("<td style=\"padding: 15px 10px; font-weight:600;\">").append(nomType).append(stockAlert).append("</td>")
                            .append("<td style=\"padding: 15px 10px;\">").append(taille).append("</td>")
                            .append("<td style=\"padding: 15px 10px; text-align: center; color:#777;\">0</td>")
                            .append("<td style=\"padding: 15px 10px; text-align: center; color:green; font-weight:600;\">").append(totalEntrees).append("</td>")
                            .append("<td style=\"padding: 15px 10px; text-align: center; color:red; font-weight:600;\">").append(totalSorties).append("</td>")
                            .append("<td style=\"padding: 15px 10px; text-align: center; font-weight: 700; color:#003366;\">").append(totalDisponible).append("</td>");
                    
                    if ("Administrateur / Gestionnaire EPI".equals(role)) {
                        stockRows.append("<td style=\"padding: 15px 10px; text-align: center;\"><a href=\"/supprimer-epi?id=").append(idType).append("&role=").append(java.net.URLEncoder.encode(role, "UTF-8")).append("\" style=\"color: #dc3545; font-weight:bold; text-decoration:none;\">Supprimer</a></td>");
                    } else {
                        stockRows.append("<td>-</td>");
                    }
                    stockRows.append("</tr>");
                    movementOptions.append("<option value=\"").append(idType).append("\">").append(nomType).append(" (").append(taille).append(")</option>");
                }
            } catch (SQLException e) { e.printStackTrace(); }

            output = output.replace("</head>", "<style>@keyframes blink { 0% { opacity: 1; } 50% { opacity: 0.6; } 100% { opacity: 1; } }</style>\n</head>");
            output = output.replace("<select name=\"id_type\" id=\"select-epi-mouvement\" required>",
                            "<select name=\"id_type\" id=\"select-epi-mouvement\" required>\n" + movementOptions)
                    .replace("<tbody id=\"table-stock-body\">", "<tbody id=\"table-stock-body\">" + stockRows);

            StringBuilder demandRows = new StringBuilder();
            if ("Chef de service".equals(role) || "Responsable de service".equals(role)) {
                try (Connection conn = connection();
                     PreparedStatement ps = conn.prepareStatement("SELECT * FROM demandes_epi");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String demandeId = resultValue(rs, "id_demande", "id");
                        demandRows.append("<tr>")
                                .append("<td style=\"padding:10px;\">").append(escapeHtml(resultValue(rs, "matricule", "matricule_employe"))).append("</td>")
                                .append("<td style=\"padding:10px;\">").append(escapeHtml(resultValue(rs, "nom_agent", "nom"))).append(" ")
                                .append(escapeHtml(resultValue(rs, "prenom_agent", "prenom"))).append("</td>")
                                .append("<td style=\"padding:10px;\">").append(escapeHtml(resultValue(rs, "service", "service_direction"))).append("</td>")
                                .append("<td style=\"padding:10px;\">").append(escapeHtml(resultValue(rs, "equipement", "nom_type", "nom_epi"))).append("</td>")
                                .append("<td style=\"padding:10px;text-align:center;\">").append(escapeHtml(resultValue(rs, "quantite", "quantite_demandee"))).append("</td>")
                                .append("<td style=\"padding:10px;text-align:center;\">").append(escapeHtml(resultValue(rs, "statut", "status"))).append("</td>")
                                .append("<td style=\"padding:10px;text-align:center;\"><button type=\"button\" data-id=\"")
                                .append(escapeHtml(demandeId))
                                .append("\" style=\"background-color:#198754;color:white;border:0;border-radius:4px;padding:8px 12px;font-weight:700;cursor:pointer;\">Approuver Besoin ✔</button></td>")
                                .append("</tr>");
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            output = output.replace("<!-- LIGNES_DEMANDES_CHEF -->", demandRows.toString());
            if ("Chef de service".equals(role) || "Responsable de service".equals(role)) {
                output = output.replace("id=\"section-validation-chef\" style=\"display: none;\"", "id=\"section-validation-chef\" style=\"background: white; padding: 30px; border-radius: 8px; border: 1px solid #e5e5e5; box-shadow: 0 4px 15px rgba(0,0,0,0.02); margin-top: 30px; display: block;\"");
            }

        // SÉCURITÉ ACTEURS : Si ce n'est pas l'admin, on cache le petit 1 et les commandes
        if (!"Administrateur / Gestionnaire EPI".equals(role)) {
            output = output.replace("id=\"section-attribution-epi\" style=\"", "id=\"section-attribution-epi\" style=\"display: none; ")
                           .replace("href=\"/commandes.html\" style=\"", "href=\"/commandes.html\" style=\"display: none; ")
                           .replace("2. ENREGISTRER MOUVEMENT", "<!-- Masqué pour les employés -->")
                           .replace("2. Enregistrer Mouvement", "<!-- Masqué pour les employés -->")
                           .replace("<div id=\"section-mouvement-stock\" style=\"", "<div id=\"section-mouvement-stock\" style=\"display: none; ")
                           .replace("<form action=\"/mouvement-stock\" method=\"POST\" style=\"display: flex; flex-direction: column; gap: 15px;\">",
                                    "<form action=\"/mouvement-stock\" method=\"POST\" style=\"display: none;\">");
        }

        sendHtml(exchange, 200, output);
    }
    }

    private static String setSectionVisibility(String html, String sectionId, boolean visible) {
            String expression = "(<div\\b[^>]*\\bid=\"" + java.util.regex.Pattern.quote(sectionId)
                + "\"[^>]*\\bstyle=\")([^\"]*)(\")";
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(expression,
                java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher matcher = pattern.matcher(html);
            StringBuffer result = new StringBuffer();

            while (matcher.find()) {
                String style = matcher.group(2).replaceAll("(?i)display\\s*:\\s*[^;\"]*;?", "").trim();
                String replacementStyle = (visible ? "display: block;" : "display: none;")
                    + (style.isEmpty() ? "" : " " + style);
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(
                    matcher.group(1) + replacementStyle + matcher.group(3)));
            }
            matcher.appendTail(result);
            return result.toString();
            }

        private static String setActorsLinkVisibility(String html, boolean visible) {
            String expression = "(<a\\b[^>]*\\bid=\"btn-gerer-acteurs\"[^>]*\\bstyle=\")([^\"]*)(\")";
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(expression,
                    java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher matcher = pattern.matcher(html);
            StringBuffer result = new StringBuffer();

            while (matcher.find()) {
                String style = matcher.group(2).replaceAll("(?i)display\\s*:\\s*[^;\"]*;?", "").trim();
                String replacementStyle = (visible ? "display: inline-block;" : "display: none;")
                        + (style.isEmpty() ? "" : " " + style);
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(
                        matcher.group(1) + replacementStyle + matcher.group(3)));
            }
            matcher.appendTail(result);
            return result.toString();
        }

    static class ActeursPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Methode non autorisee");
                return;
            }

            String html = readFile("web/acteurs.html");
            StringBuilder personnelRows = new StringBuilder();
            StringBuilder fournisseurRows = new StringBuilder();

            try (Connection conn = connection();
                 PreparedStatement personnel = conn.prepareStatement(
                     "SELECT matricule, nom, prenom, service FROM personnel ORDER BY nom, prenom");
                 ResultSet personnelResult = personnel.executeQuery()) {
                while (personnelResult.next()) {
                    personnelRows.append("<tr><td>")
                            .append(escapeHtml(personnelResult.getString("matricule")))
                            .append("</td><td>")
                            .append(escapeHtml(personnelResult.getString("nom")))
                            .append(" ")
                            .append(escapeHtml(personnelResult.getString("prenom")))
                            .append("</td><td>")
                            .append(escapeHtml(personnelResult.getString("service")))
                            .append("</td><td>")
                            .append("<span title=\"Modifier\" style=\"margin-right:8px; cursor:pointer;\">📝</span>")
                            .append("<a class=\"supprimer-acteur\" title=\"Supprimer\" href=\"/supprimer-acteur-personnel?id=")
                            .append(encode(personnelResult.getString("matricule")))
                            .append("\">🗑️</a></td></tr>");
                }

                try (PreparedStatement fournisseurs = conn.prepareStatement(
                        "SELECT id_fournisseur, nom_entreprise, contact, adresse FROM partenaires_fournisseurs ORDER BY nom_entreprise");
                     ResultSet fournisseurResult = fournisseurs.executeQuery()) {
                    while (fournisseurResult.next()) {
                        fournisseurRows.append("<tr><td>")
                                .append(escapeHtml(fournisseurResult.getString("nom_entreprise")))
                                .append("</td><td>")
                                .append(escapeHtml(fournisseurResult.getString("contact")))
                                .append("</td><td>")
                                .append(escapeHtml(fournisseurResult.getString("adresse")))
                                .append("</td><td>")
                                .append("<span title=\"Modifier\" style=\"margin-right:8px; cursor:pointer;\">📝</span>")
                                .append("<a class=\"supprimer-acteur\" title=\"Supprimer\" href=\"/supprimer-fournisseur?id=")
                                .append(fournisseurResult.getInt("id_fournisseur"))
                                .append("\">🗑️</a></td></tr>");
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            html = html.replace("<!-- LIGNES_PERSONNEL -->", personnelRows.toString())
                    .replace("<!-- LIGNES_FOURNISSEURS -->", fournisseurRows.toString());
            sendHtml(exchange, 200, html);
        }
    }

    static class AjouterPersonnelHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Methode non autorisee");
                return;
            }

            Map<String, String> form = parseFormData(readBody(exchange));
            try (Connection conn = connection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO personnel (matricule, nom, prenom, service) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, form.get("matricule"));
                ps.setString(2, form.get("nom"));
                ps.setString(3, form.get("prenom"));
                ps.setString(4, form.get("service"));
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
            redirect(exchange, "/acteurs.html");
        }
    }

    static class AjouterFournisseurHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Methode non autorisee");
                return;
            }

            Map<String, String> form = parseFormData(readBody(exchange));
            try (Connection conn = connection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO partenaires_fournisseurs (nom_entreprise, contact, adresse) VALUES (?, ?, ?)")) {
                ps.setString(1, form.get("nom_entreprise"));
                ps.setString(2, form.get("contact"));
                ps.setString(3, form.get("adresse"));
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
            redirect(exchange, "/acteurs.html");
        }
    }

    static class SupprimerPersonnelHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Methode non autorisee");
                return;
            }
            try (Connection conn = connection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM personnel WHERE matricule = ?")) {
                ps.setString(1, queryValue(exchange, "id"));
                ps.executeUpdate();
                sendResponse(exchange, 200, "OK");
            } catch (SQLException e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Erreur lors de la suppression");
            }
        }
    }

    static class SupprimerFournisseurHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Methode non autorisee");
                return;
            }
            try (Connection conn = connection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM partenaires_fournisseurs WHERE id_fournisseur = ?")) {
                ps.setInt(1, integer(queryValue(exchange, "id")));
                ps.executeUpdate();
                sendResponse(exchange, 200, "OK");
            } catch (SQLException | NumberFormatException e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Erreur lors de la suppression");
            }
        }
    }

    static class DistributionsPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = readFile("web/distributions.html");
            String query = exchange.getRequestURI().getQuery();
            String role = "Profil non identifie";
            if (query != null && query.contains("role=")) {
                role = parseFormData(query).getOrDefault("role", role);
            }
            if (isTerrainRole(role)) {
                sendResponse(exchange, 403, "403 Interdit");
                return;
            }
            boolean administrator = "Administrateur / Gestionnaire EPI".equals(role);
            StringBuilder options = new StringBuilder();
            StringBuilder rows = new StringBuilder();
            try (Connection conn = connection();
                 PreparedStatement types = conn.prepareStatement("SELECT id_type, nom_type, taille_pointure FROM types_epi ORDER BY nom_type");
                 ResultSet typeResult = types.executeQuery()) {
                while (typeResult.next()) {
                    options.append("<option value=\"").append(typeResult.getInt("id_type")).append("\">")
                            .append(escapeHtml(typeResult.getString("nom_type"))).append(" (")
                            .append(escapeHtml(typeResult.getString("taille_pointure"))).append(")</option>");
                }
                String sql = "SELECT d.id_distribution, d.matricule_employe, d.nom_employe, d.prenom_employe, "
                        + "d.service_direction, d.quantite_distribuee, d.date_distribution, t.nom_type, t.taille_pointure "
                        + "FROM distributions d JOIN types_epi t ON t.id_type = d.id_type ORDER BY d.date_distribution DESC";
                try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet result = ps.executeQuery()) {
                    while (result.next()) {
                        rows.append("<tr><td style=\"padding:12px;border:1px solid #ddd;\">")
                                .append(escapeHtml(result.getString("matricule_employe"))).append("</td><td style=\"padding:12px;border:1px solid #ddd;\">")
                                .append(escapeHtml(result.getString("nom_employe"))).append(" ").append(escapeHtml(result.getString("prenom_employe")))
                                .append("</td><td style=\"padding:12px;border:1px solid #ddd;\">").append(escapeHtml(result.getString("service_direction")))
                                .append("</td><td style=\"padding:12px;border:1px solid #ddd;\">").append(escapeHtml(result.getString("nom_type")))
                                .append(" (").append(escapeHtml(result.getString("taille_pointure"))).append(")</td><td style=\"padding:12px;text-align:center;border:1px solid #ddd;\">")
                                .append(result.getInt("quantite_distribuee")).append("</td><td style=\"padding:12px;text-align:center;border:1px solid #ddd;\">")
                                .append(result.getString("date_distribution")).append("</td>");
                        if (administrator) {
                            rows.append("<td style=\"padding:12px;text-align:center;border:1px solid #ddd;\">")
                                    .append("<a href=\"/modifier-distribution?id=").append(result.getInt("id_distribution"))
                                    .append("\">Modifier</a> <a href=\"/supprimer-distribution?id=").append(result.getInt("id_distribution"))
                                    .append("\">Supprimer</a></td>");
                        }
                        rows.append("</tr>");
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            html = html.replace("<!-- Rempli dynamiquement par Java -->", options.toString())
                    .replace("<!-- LIGNES_DISTRIBUTIONS -->", rows.toString());
            if (!administrator) {
                html = html.replace("<section style=\"background: rgba(255, 255, 255, 0.85); backdrop-filter: blur(10px); padding: 25px; border-radius: 8px; border: 1px solid #ddd; margin-bottom: 30px;\">",
                        "<section id=\"section-attribution-epi\" style=\"display: none;\">")
                    .replace("id=\"section-attribution-epi\" style=\"", "id=\"section-attribution-epi\" style=\"display: none; ")
                    .replace("<form action=\"/attribuer-epi\" method=\"POST\" style=\"display: flex; flex-direction: column; gap: 15px;\">",
                                "<form action=\"/attribuer-epi\" method=\"POST\" style=\"display: none;\">")
                    .replace("<a href=\"/commandes.html\" style=\"background-color: #003366;",
                        "<a href=\"/commandes.html\" style=\"display: none; background-color: #003366;")
                        .replace("<th style=\"padding: 12px; text-align: center; border: 1px solid #ddd; border-right: none;\">Action</th>", "");
            }
            sendHtml(exchange, 200, html);
        }
    }

    static class MonEspaceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Methode non autorisee");
                return;
            }

            String matricule = queryValue(exchange, "matricule");
            if (matricule.isEmpty()) {
                sendResponse(exchange, 403, "403 Interdit");
                return;
            }

            String fonction = "Fonction non identifiee";
            StringBuilder rows = new StringBuilder();
            String sql = "SELECT d.quantite_distribuee, d.date_distribution, t.nom_type, t.taille_pointure "
                    + "FROM distributions d JOIN types_epi t ON t.id_type = d.id_type "
                    + "WHERE d.matricule_employe = ? ORDER BY d.date_distribution DESC";
            try (Connection conn = connection();
                 PreparedStatement user = conn.prepareStatement("SELECT role FROM utilisateurs WHERE matricule = ?");
                 PreparedStatement distributions = conn.prepareStatement(sql)) {
                user.setString(1, matricule);
                try (ResultSet result = user.executeQuery()) {
                    if (result.next()) fonction = result.getString("role");
                }

                distributions.setString(1, matricule);
                try (ResultSet result = distributions.executeQuery()) {
                    while (result.next()) {
                        rows.append("<tr><td>")
                                .append(escapeHtml(result.getString("nom_type"))).append(" (")
                                .append(escapeHtml(result.getString("taille_pointure"))).append(")</td><td style=\"text-align:center;\">")
                                .append(result.getInt("quantite_distribuee")).append("</td><td>")
                                .append(escapeHtml(result.getString("date_distribution"))).append("</td></tr>");
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            String html = readFile("web/mon_espace.html")
                    .replace("<!-- MATRICULE_AGENT -->", escapeHtml(matricule))
                    .replace("<!-- FONCTION_AGENT -->", escapeHtml(fonction))
                    .replace("<!-- LIGNES_DOTATIONS_PERSONNELLES -->", rows.toString());
            sendHtml(exchange, 200, html);
        }
    }

    static class AttribuerEpiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Methode non autorisee");
                return;
            }
            Map<String, String> form = parseFormData(readBody(exchange));
            try (Connection conn = connection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement distribution = conn.prepareStatement(
                        "INSERT INTO distributions (matricule_employe, nom_employe, prenom_employe, service_direction, id_type, quantite_distribuee) VALUES (?, ?, ?, ?, ?, ?)");
                     PreparedStatement movement = conn.prepareStatement(
                             "INSERT INTO historique_mouvements (id_type, quantite, type_mouvement) VALUES (?, ?, 'Sortie')")) {
                    distribution.setString(1, form.get("matricule_employe"));
                    distribution.setString(2, form.get("nom_employe"));
                    distribution.setString(3, form.get("prenom_employe"));
                    distribution.setString(4, form.get("service_direction"));
                    distribution.setInt(5, integer(form.get("id_type")));
                    distribution.setInt(6, integer(form.get("quantite_distribuee")));
                    distribution.executeUpdate();
                    movement.setInt(1, integer(form.get("id_type")));
                    movement.setInt(2, integer(form.get("quantite_distribuee")));
                    movement.executeUpdate();
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            redirect(exchange, "/distributions.html");
        }
    }

    static class SupprimerDistributionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int id = integer(queryValue(exchange, "id"));
            try (Connection conn = connection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement select = conn.prepareStatement("SELECT id_type, quantite_distribuee FROM distributions WHERE id_distribution = ?");
                     PreparedStatement delete = conn.prepareStatement("DELETE FROM distributions WHERE id_distribution = ?");
                     PreparedStatement movement = conn.prepareStatement("INSERT INTO historique_mouvements (id_type, quantite, type_mouvement) VALUES (?, ?, 'Entree')")) {
                    select.setInt(1, id);
                    try (ResultSet result = select.executeQuery()) {
                        if (result.next()) {
                            movement.setInt(1, result.getInt("id_type"));
                            movement.setInt(2, result.getInt("quantite_distribuee"));
                            movement.executeUpdate();
                            delete.setInt(1, id);
                            delete.executeUpdate();
                        }
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            sendResponse(exchange, 200, "OK");
        }
    }

    static class ModifierDistributionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Utilisez POST pour modifier une distribution.");
                return;
            }
            Map<String, String> form = parseFormData(readBody(exchange));
            try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(
                    "UPDATE distributions SET matricule_employe = ?, nom_employe = ?, prenom_employe = ?, service_direction = ?, quantite_distribuee = ? WHERE id_distribution = ?")) {
                ps.setString(1, form.get("matricule_employe"));
                ps.setString(2, form.get("nom_employe"));
                ps.setString(3, form.get("prenom_employe"));
                ps.setString(4, form.get("service_direction"));
                ps.setInt(5, integer(form.get("quantite_distribuee")));
                ps.setInt(6, integer(form.get("id")));
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
            redirect(exchange, "/distributions.html");
        }
    }

    static class CommandesPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Methode non autorisee");
                return;
            }

            String html = readFile("web/commandes.html");
            StringBuilder options = new StringBuilder();
            StringBuilder rows = new StringBuilder();
            try (Connection conn = connection();
                 PreparedStatement types = conn.prepareStatement("SELECT id_type, nom_type, taille_pointure FROM types_epi ORDER BY nom_type");
                 ResultSet typeResult = types.executeQuery()) {
                while (typeResult.next()) {
                    options.append("<option value=\"").append(typeResult.getInt("id_type")).append("\">")
                            .append(escapeHtml(typeResult.getString("nom_type"))).append(" (")
                            .append(escapeHtml(typeResult.getString("taille_pointure"))).append(")</option>");
                }

                String sql = "SELECT c.id_commande, c.numero_bon, c.nom_fournisseur, c.quantite_commandee, "
                        + "c.prix_unitaire_ht, c.statut, t.nom_type, t.taille_pointure "
                        + "FROM commandes c LEFT JOIN types_epi t ON t.id_type = c.id_type "
                        + "ORDER BY c.id_commande DESC";
                try (PreparedStatement commands = conn.prepareStatement(sql);
                     ResultSet result = commands.executeQuery()) {
                    while (result.next()) {
                        String status = result.getString("statut");
                        boolean received = status != null && "RECU".equalsIgnoreCase(status.trim());
                        java.math.BigDecimal unitPrice = result.getBigDecimal("prix_unitaire_ht");
                        int quantity = result.getInt("quantite_commandee");
                        java.math.BigDecimal total = unitPrice == null
                                ? java.math.BigDecimal.ZERO
                                : unitPrice.multiply(java.math.BigDecimal.valueOf(quantity));

                        rows.append("<tr><td>").append(escapeHtml(result.getString("numero_bon"))).append("</td><td>")
                                .append(escapeHtml(result.getString("nom_fournisseur"))).append("</td><td>")
                                .append(escapeHtml(result.getString("nom_type"))).append(" (")
                                .append(escapeHtml(result.getString("taille_pointure"))).append(")</td><td style=\"text-align:center;\">")
                                .append(quantity).append("</td><td>").append(total).append(" Ar</td>")
                                .append("<td style=\"text-align:center;\"><span class=\"badge ")
                                .append(received ? "badge-recu\">Recue" : "badge-attente\">En attente")
                                .append("</span></td><td style=\"text-align:center;\">");
                        if (!received) {
                            rows.append("<a class=\"btn-action\" href=\"/recevoir-commande?id=")
                                    .append(result.getInt("id_commande"))
                                    .append("\">Marquer recue</a>");
                        } else {
                            rows.append("-");
                        }
                        rows.append("</td></tr>");
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Erreur lors du chargement des commandes");
                return;
            }
            html = html.replace("<!-- Rempli dynamiquement par Java -->", options.toString())
                    .replace("<!-- LIGNES_COMMANDES -->", rows.toString());
            sendHtml(exchange, 200, html);
        }
    }

       static class CreerCommandeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Methode non autorisee");
                return;
            }
            
            Map<String, String> form = parseFormData(readBody(exchange));
            String numeroBon = form.get("numero_bon");
            String nomFournisseur = form.get("nom_fournisseur");
            String idType = form.get("id_type");
            String quantiteCommandee = form.get("quantite_commandee");
            String prixUnitaireHt = form.get("prix_unitaire_ht");

            if (numeroBon == null || numeroBon.trim().isEmpty() || 
                nomFournisseur == null || nomFournisseur.trim().isEmpty() || 
                idType == null || idType.trim().isEmpty() || 
                quantiteCommandee == null || quantiteCommandee.trim().isEmpty() || 
                prixUnitaireHt == null || prixUnitaireHt.trim().isEmpty()) {
                
                exicherEcranErreur(exchange, "Veuillez sélectionner un équipement (EPI) valide et remplir toutes les cases du formulaire.");
                return;
            }

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                int id = Integer.parseInt(idType);
                int qte = Integer.parseInt(quantiteCommandee);
                double pu = Double.parseDouble(prixUnitaireHt);
                double totalTTC = qte * pu * 1.20;

                String sql = "INSERT INTO commandes (numero_bon, nom_fournisseur, id_type, quantite_commandee, prix_unitaire_ht, montant_ttc, statut) VALUES (?, ?, ?, ?, ?, ?, 'En attente')";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, numeroBon);
                    ps.setString(2, nomFournisseur);
                    ps.setInt(3, id);
                    ps.setInt(4, qte);
                    ps.setDouble(5, pu);
                    ps.setDouble(6, totalTTC);
                    ps.executeUpdate();
                }
                
                exchange.getResponseHeaders().set("Location", "/commandes.html?t=" + System.currentTimeMillis());
                exchange.sendResponseHeaders(303, -1);
                
            } catch (Exception e) {
                e.printStackTrace();
                exicherEcranErreur(exchange, "Erreur lors de l'enregistrement. Assurez-vous d'avoir créé cet EPI dans votre stock général au préalable.");
            }
        }

        private void exicherEcranErreur(HttpExchange exchange, String message) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            String errorHtml = "<html><body style=\"font-family:sans-serif; text-align:center; padding-top:100px; background:#f8f9fa;\">"
                    + "<div style=\"max-width:500px; margin:0 auto; background:white; padding:30px; border-radius:8px; box-shadow:0 4px 15px rgba(0,0,0,0.05); border-top:5px solid #dc3545;\">"
                    + "<h2 style=\"color:#dc3545;\">❌ Problème de validation</h2>"
                    + "<p style=\"color:#555;\">" + message + "</p><br>"
                    + "<a href=\"/commandes.html\" style=\"background:#003366; color:white; padding:10px 20px; text-decoration:none; border-radius:4px; font-weight:bold;\">⬅️ Retourner aux commandes</a>"
                    + "</div></body></html>";
            byte[] bytes = errorHtml.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    static class RecevoirCommandeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int id = integer(queryValue(exchange, "id"));
            try (Connection conn = connection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement order = conn.prepareStatement("SELECT id_type, quantite_commandee FROM commandes WHERE id_commande = ?");
                     PreparedStatement update = conn.prepareStatement("UPDATE commandes SET statut = 'RECU' WHERE id_commande = ?");
                     PreparedStatement movement = conn.prepareStatement("INSERT INTO historique_mouvements (id_type, quantite, type_mouvement) VALUES (?, ?, 'Entree')")) {
                    order.setInt(1, id);
                    try (ResultSet result = order.executeQuery()) {
                        if (result.next()) {
                            movement.setInt(1, result.getInt("id_type"));
                            movement.setInt(2, result.getInt("quantite_commandee"));
                            movement.executeUpdate();
                            update.setInt(1, id);
                            update.executeUpdate();
                        }
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            redirect(exchange, "/commandes.html");
        }
    }

    static class AjouterEpiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendResponse(exchange, 405, "Methode non autorisee"); return; }
            Map<String, String> form = parseFormData(readBody(exchange));
            try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement("INSERT INTO types_epi (nom_type, taille_pointure) VALUES (?, ?)")) {
                ps.setString(1, form.get("nom_type")); ps.setString(2, form.get("taille_pointure")); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
            redirect(exchange, "/dashboard.html?role=" + encode("Administrateur / Gestionnaire EPI"));
        }
    }

    static class MouvementStockHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendResponse(exchange, 405, "Methode non autorisee"); return; }
            Map<String, String> form = parseFormData(readBody(exchange));
            try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement("INSERT INTO historique_mouvements (id_type, quantite, type_mouvement) VALUES (?, ?, ?)")) {
                ps.setInt(1, integer(form.get("id_type"))); ps.setInt(2, integer(form.get("quantite"))); ps.setString(3, form.get("type_mouvement")); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
            redirect(exchange, "/dashboard.html?role=" + encode("Administrateur / Gestionnaire EPI"));
        }
    }

    static class SupprimerEpiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement("DELETE FROM types_epi WHERE id_type = ?")) {
                ps.setInt(1, integer(queryValue(exchange, "id"))); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
            sendResponse(exchange, 200, "OK");
        }
    }

    static class CreerUtilisateurHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendResponse(exchange, 405, "Methode non autorisee"); return; }
            Map<String, String> form = parseFormData(readBody(exchange));
            try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement("INSERT INTO utilisateurs (matricule, mot_de_passe, role) VALUES (?, ?, ?)")) {
                ps.setString(1, form.get("nouveau_matricule")); ps.setString(2, form.get("nouveau_password")); ps.setString(3, form.get("nouveau_role")); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
            redirect(exchange, "/dashboard.html?role=" + encode("Administrateur / Gestionnaire EPI"));
        }
    }

    static class LogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException { redirect(exchange, "/index.html"); }
    }

    private static Connection connection() throws SQLException { return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS); }
    private static String readBody(HttpExchange exchange) throws IOException { return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); }
    private static String readFile(String path) throws IOException { return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8); }
    private static int integer(String value) { return Integer.parseInt(value == null ? "0" : value); }
    private static String encode(String value) throws IOException { return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
    private static String queryValue(HttpExchange exchange, String key) throws IOException { return parseFormData(exchange.getRequestURI().getRawQuery()).getOrDefault(key, ""); }
    private static Map<String, String> parseFormData(String data) throws IOException {
        Map<String, String> values = new HashMap<>();
        if (data == null || data.isEmpty()) return values;
        for (String pair : data.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], "UTF-8"), parts.length > 1 ? URLDecoder.decode(parts[1], "UTF-8") : "");
        }
        return values;
    }
    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static String resultValue(ResultSet result, String... columnNames) throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        for (String columnName : columnNames) {
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                if (columnName.equalsIgnoreCase(metadata.getColumnLabel(index))
                        || columnName.equalsIgnoreCase(metadata.getColumnName(index))) {
                    Object value = result.getObject(index);
                    return value == null ? "" : value.toString();
                }
            }
        }
        return "";
    }
    private static void redirect(HttpExchange exchange, String location) throws IOException { exchange.getResponseHeaders().set("Location", location); exchange.sendResponseHeaders(303, -1); exchange.close(); }
    private static void sendHtml(HttpExchange exchange, int status, String body) throws IOException { exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8"); sendResponse(exchange, status, body); }
    private static void sendResponse(HttpExchange exchange, int status, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length); try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); } }
    
    // Méthode de sécurité indispensable pour identifier les employés de terrain du port
    private static boolean isTerrainRole(String role) {
        if (role == null) return true;
        String r = role.trim();
        return !"Administrateur / Gestionnaire EPI".equals(r) && 
               !"Responsable HSTE".equals(r) && 
               !"Chef de service".equals(r) && 
               !"Responsable de service".equals(r);
    }

}
