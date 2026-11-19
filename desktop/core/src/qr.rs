//! Dibujo del QR de emparejamiento (SVG para la interfaz, texto para la terminal).

use qrcode::render::{svg, unicode};
use qrcode::QrCode;

/// QR como SVG (fondo claro con margen, para que cualquier cámara lo lea también en tema oscuro).
pub fn svg(text: &str) -> Option<String> {
    let code = QrCode::new(text.as_bytes()).ok()?;
    Some(
        code.render::<svg::Color>()
            .min_dimensions(220, 220)
            .quiet_zone(true)
            .dark_color(svg::Color("#000000"))
            .light_color(svg::Color("#ffffff"))
            .build(),
    )
}

/// QR en bloques Unicode, de media altura, para imprimir en la terminal.
pub fn terminal(text: &str) -> Option<String> {
    let code = QrCode::new(text.as_bytes()).ok()?;
    Some(code.render::<unicode::Dense1x2>().quiet_zone(true).build())
}
