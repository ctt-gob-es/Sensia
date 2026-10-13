

module.exports = {
  pdf_options: {
    format: "A4",
    margin: {
      top: "25mm",
      right: "20mm",
      bottom: "25mm",
      left: "20mm"
    },
    displayHeaderFooter: false,
    footerTemplate: "<div style='font-family: Arial; font-size: 10px; width: 100%; text-align: center;'>Página <span class='pageNumber'></span> de <span class='totalPages'></span></div>"
  },

  launch_options: {
    executablePath: process.env.CHROME_BIN || "/usr/bin/chromium",
    args: [
      "--no-sandbox",
      "--disable-setuid-sandbox",
      "--disable-dev-shm-usage"
    ]
  },

  stylesheet: [
    "config/pdf-theme.css"
  ]
};