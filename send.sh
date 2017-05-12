echo "Enter your ist ID (eg: ist178471):"
read text
tar -czf proj.tar.gz RenderFarm
scp proj.tar.gz $text@sigma.ist.utl.pt:web/
rm proj.tar.gz
