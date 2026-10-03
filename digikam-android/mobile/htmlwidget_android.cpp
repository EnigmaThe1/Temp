// Android-safe replacement for digiKam's Qt WebEngine map HTML widget.
#include "htmlwidget_qwebengine.h"

#include <QLabel>
#include <QTimer>
#include <QVBoxLayout>

namespace Digikam
{

HTMLWidget::HTMLWidget(QWidget* const parent)
    : QWidget(parent)
{
    setAcceptDrops(false);
    setFocusPolicy(Qt::WheelFocus);
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding);

    QVBoxLayout* const layout = new QVBoxLayout(this);
    QLabel* const label       = new QLabel(
        tr("Online map preview is not available in this Android build."),
        this
    );

    label->setWordWrap(true);
    label->setAlignment(Qt::AlignCenter);
    layout->addWidget(label);
}

HTMLWidget::~HTMLWidget() = default;

void HTMLWidget::loadInitialHTML(const QString& initialHTML)
{
    Q_UNUSED(initialHTML);

    QTimer::singleShot(0, this,
                       [this]()
        {
            Q_EMIT signalJavaScriptReady();
        });
}

QVariant HTMLWidget::runScript(const QString& scriptCode, bool async)
{
    Q_UNUSED(scriptCode);
    Q_UNUSED(async);

    return QVariant();
}

bool HTMLWidget::runScript2Coordinates(const QString& scriptCode,
                                       GeoCoordinates* const coordinates)
{
    Q_UNUSED(scriptCode);
    Q_UNUSED(coordinates);

    return false;
}

void HTMLWidget::mouseModeChanged(const GeoMouseModes mouseMode)
{
    Q_UNUSED(mouseMode);
}

void HTMLWidget::setSelectionRectangle(const GeoCoordinates::Pair& searchCoordinates)
{
    Q_UNUSED(searchCoordinates);
}

void HTMLWidget::removeSelectionRectangle()
{
}

void HTMLWidget::centerOn(qreal west, qreal north, qreal east, qreal south,
                          bool useSaneZoomLevel)
{
    Q_UNUSED(west);
    Q_UNUSED(north);
    Q_UNUSED(east);
    Q_UNUSED(south);
    Q_UNUSED(useSaneZoomLevel);
}

void HTMLWidget::setSharedGeoIfaceObject(GeoIfaceSharedData* const sharedData)
{
    s = sharedData;
    Q_UNUSED(s);
}

bool HTMLWidget::eventFilter(QObject* object, QEvent* event)
{
    return QWidget::eventFilter(object, event);
}

void HTMLWidget::slotHTMLCompleted(bool ok)
{
    if (ok)
    {
        Q_EMIT signalJavaScriptReady();
    }
}

void HTMLWidget::progress(int progressValue)
{
    Q_UNUSED(progressValue);
}

} // namespace Digikam

#include "moc_htmlwidget_qwebengine.cpp"
