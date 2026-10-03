// Android compatibility implementation for digiKam's WebBrowserDlg.
#include "webbrowserdlg.h"

#include <QDesktopServices>
#include <QIcon>
#include <QTimer>

namespace Digikam
{

class Q_DECL_HIDDEN WebBrowserDlg::Private
{
public:

    QUrl home;
};

WebBrowserDlg::WebBrowserDlg(const QUrl& url,
                             QWidget* const parent,
                             bool hideDeskBrowser)
    : QDialog(parent),
      d      (new Private)
{
    Q_UNUSED(hideDeskBrowser);

    d->home = url;

    setWindowTitle(tr("Open web link"));
    setAttribute(Qt::WA_DeleteOnClose, true);

    // Embedded Qt WebEngine is unavailable on Qt5 Android. Hand the URL to
    // Android's registered browser and close this compatibility dialog.
    QTimer::singleShot(0, this,
                       [this]()
        {
            slotDesktopWebBrowser();
            close();
        });
}

WebBrowserDlg::~WebBrowserDlg()
{
    delete d;
}

void WebBrowserDlg::slotUrlChanged(const QUrl& url)
{
    d->home = url;
    Q_EMIT urlChanged(url);
}

void WebBrowserDlg::slotLoadingStarted()
{
}

void WebBrowserDlg::slotLoadingFinished(bool ok)
{
    Q_UNUSED(ok);
}

void WebBrowserDlg::slotIconChanged(const QIcon& icon)
{
    Q_UNUSED(icon);
}

void WebBrowserDlg::slotTitleChanged(const QString& title)
{
    if (!title.isEmpty())
    {
        setWindowTitle(title);
    }
}

void WebBrowserDlg::slotSearchTextChanged(const SearchTextSettings& settings)
{
    Q_UNUSED(settings);
}

void WebBrowserDlg::slotGoHome()
{
    slotDesktopWebBrowser();
}

void WebBrowserDlg::slotDesktopWebBrowser()
{
    if (d->home.isValid())
    {
        QDesktopServices::openUrl(d->home);
    }
}

void WebBrowserDlg::closeEvent(QCloseEvent* event)
{
    Q_EMIT closeView(true);
    QDialog::closeEvent(event);
}

} // namespace Digikam

#include "moc_webbrowserdlg.cpp"
