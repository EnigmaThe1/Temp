// Android compatibility replacement for digiKam's embedded WebEngine welcome page.
#include "welcomepageview.h"

#include <QDesktopServices>

namespace Digikam
{

WelcomePageView::WelcomePageView(QWidget* const parent)
    : QTextBrowser(parent)
{
    setFocusPolicy(Qt::WheelFocus);
    setContextMenuPolicy(Qt::NoContextMenu);
    setOpenLinks(false);
    setOpenExternalLinks(false);
    setReadOnly(true);

    connect(this, SIGNAL(anchorClicked(QUrl)),
            this, SLOT(slotUrlOpen(QUrl)));

    slotThemeChanged();
}

void WelcomePageView::slotUrlOpen(const QUrl& url)
{
    QDesktopServices::openUrl(url);
}

void WelcomePageView::slotThemeChanged()
{
    setHtml(
        tr("<h2>digiKam</h2>"
           "<p>Photo management adapted for Android.</p>"
           "<p><a href=\"https://www.digikam.org\">digiKam website</a> &nbsp; "
           "<a href=\"https://docs.digikam.org\">Documentation</a></p>")
    );
}

} // namespace Digikam

#include "moc_welcomepageview.cpp"
